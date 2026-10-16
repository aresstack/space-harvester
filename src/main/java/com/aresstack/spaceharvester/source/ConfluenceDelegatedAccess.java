package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccess;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccessFactory;
import com.aresstack.spaceharvester.application.access.DelegationUnavailableException;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.access.UserCredential;
import com.aresstack.spaceharvester.source.confluence.DelegatedConfluenceHttp;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prüfung mit dem durchgereichten Token eines Endnutzers bei Confluence Data Center (#24, Teil C), über dieselbe Route
 * und dasselbe Client-Zertifikat wie das Ernten ({@link DelegatedConfluenceHttp}).
 *
 * <ul>
 *   <li>Wer: {@code GET /rest/api/user/current}; nur ein angemeldeter Benutzer mit {@code userKey} oder
 *       {@code username} zählt. 401, 403, Weiterleitungen (Login) und anonyme Antworten lehnen das Token ab.</li>
 *   <li>Seite: {@code GET /rest/api/content/{id}?expand=space}; frei nur bei 200 mit dieser ID und, falls bekannt,
 *       demselben Space.</li>
 *   <li>Space: {@code GET /rest/api/space/{key}}; frei nur bei 200 mit diesem Key.</li>
 *   <li>401, 403, 404 und Weiterleitungen: nicht sichtbar. Alles andere, Zeitüberschreitung und Verbindungsfehler:
 *       unklar, der Zugriff wird verweigert.</li>
 * </ul>
 *
 * Das Token erscheint weder in Logs noch in Ausnahmen; geloggt werden nur Quelle, Status und Ausnahmetyp.
 */
public final class ConfluenceDelegatedAccess implements DelegatedSourceAccess {

    private static final Logger LOG = LoggerFactory.getLogger(ConfluenceDelegatedAccess.class);
    /** Confluence-IDs und Space-Keys (auch persönliche mit {@code ~}); alles andere wird nicht angefragt. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("~?[A-Za-z0-9_-]{1,255}");
    /** Über der Zeitgrenze des Transports, damit dessen Abbruch zuerst greift. */
    private static final long WAIT_SECONDS = 15;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String sourceId;
    private final DelegatedConfluenceHttp http;

    ConfluenceDelegatedAccess(String sourceId, DelegatedConfluenceHttp http) {
        this.sourceId = sourceId;
        this.http = http;
    }

    /** Delegierte Prüfung für jede Confluence-Quelle des Katalogs; andere Quellen haben keine. */
    public static DelegatedSourceAccessFactory factory(ConfiguredSourceCatalog catalog) {
        return sourceId -> catalog.delegatedHttp(sourceId)
                .map(http -> new ConfluenceDelegatedAccess(sourceId, http));
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public Optional<String> identify(UserCredential credential) {
        requireOwn(credential);
        ConfluenceHttpResponse response = get("/rest/api/user/current", credential)
                .orElseThrow(() -> new DelegationUnavailableException(sourceId));
        int status = response.status();
        if (status == 401 || status == 403 || isRedirect(status)) {
            return Optional.empty();
        }
        JsonNode user = status == 200 ? json(response) : null;
        if (user == null) {
            LOG.warn("Quelle {}: unklare Antwort auf die Benutzerabfrage (Status {})", sourceId, status);
            throw new DelegationUnavailableException(sourceId);
        }
        if ("anonymous".equals(text(user, "type"))) {
            return Optional.empty();
        }
        String id = text(user, "userKey");
        if (id == null) {
            id = text(user, "username");
        }
        if (id == null) {
            throw new DelegationUnavailableException(sourceId);
        }
        return Optional.of(id);
    }

    @Override
    public AccessDecision canSee(UserCredential credential, ResourceScope scope) {
        requireOwn(credential);
        if (!scope.sourceId().equals(sourceId) || scope.isSource()) {
            return AccessDecision.UNDETERMINED;
        }
        if (scope.pageId() != null) {
            if (!SAFE_SEGMENT.matcher(scope.pageId()).matches()) {
                return AccessDecision.DENY;
            }
            return decide(get("/rest/api/content/" + scope.pageId() + "?expand=space", credential), page -> {
                if (!scope.pageId().equals(text(page, "id"))) {
                    return false;
                }
                String spaceKey = page.path("space").path("key").asString(null);
                return scope.spaceKey() == null ? spaceKey != null : scope.spaceKey().equals(spaceKey);
            });
        }
        if (!SAFE_SEGMENT.matcher(scope.spaceKey()).matches()) {
            return AccessDecision.DENY;
        }
        return decide(get("/rest/api/space/" + scope.spaceKey(), credential),
                space -> scope.spaceKey().equals(text(space, "key")));
    }

    private AccessDecision decide(Optional<ConfluenceHttpResponse> answer,
                                  java.util.function.Predicate<JsonNode> matches) {
        if (answer.isEmpty()) {
            return AccessDecision.UNDETERMINED;
        }
        ConfluenceHttpResponse response = answer.get();
        int status = response.status();
        if (status == 401 || status == 403 || status == 404 || isRedirect(status)) {
            return AccessDecision.DENY;
        }
        if (status == 200) {
            JsonNode body = json(response);
            if (body != null) {
                return matches.test(body) ? AccessDecision.ALLOW : AccessDecision.DENY;
            }
        }
        LOG.warn("Quelle {}: unklare Antwort auf die Rechteprüfung (Status {})", sourceId, status);
        return AccessDecision.UNDETERMINED;
    }

    /** Die Antwort, oder leer bei Verbindungsfehler, Zeitüberschreitung oder zu großer Antwort. */
    private Optional<ConfluenceHttpResponse> get(String path, UserCredential credential) {
        try {
            return Optional.of(http.get(path, credential.token()).toCompletableFuture()
                    .get(WAIT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException | CompletionException | TimeoutException | IllegalArgumentException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.warn("Quelle {}: delegierte Abfrage fehlgeschlagen ({})", sourceId, cause.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void requireOwn(UserCredential credential) {
        if (!credential.sourceId().equals(sourceId)) {
            throw new IllegalArgumentException("Token gehört zu einer anderen Quelle");
        }
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private static JsonNode json(ConfluenceHttpResponse response) {
        String type = response.contentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(response.body());
            return node != null && node.isObject() ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
