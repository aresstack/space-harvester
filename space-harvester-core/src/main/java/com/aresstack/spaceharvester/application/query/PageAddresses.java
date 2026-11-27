package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adressauflösung über alle Quellen (#178, wie {@code ResolveKnowledgeAddressUseCase} im Enterprise AI Client): jede
 * Quelle, deren Adressmuster passt, wird einzeln gefragt; geraten wird nie zwischen Quellen. Zählen nur Lesezeichen
 * und was die Quelle selbst zuordnet; eine Seiten-ID aus der URL einer anderen Instanz wird nirgends abgerufen (mit
 * Quelle bleibt das über {@link PageQueries#pageByUrl} möglich). Ein Kandidat erscheint nur, wenn der Aufrufer die Seite lesen darf; eine nicht erreichbare Quelle
 * verdeckt die anderen nicht und steht in {@link Resolution#unavailableSources()}.
 */
public final class PageAddresses {

    private final SourceCatalog catalog;
    private final AccessGuard access;
    private final PageQueries pages;

    public PageAddresses(SourceCatalog catalog, AccessGuard access, PageQueries pages) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /**
     * @param address URL, Lesezeichen {@code space-harvester://…} oder Ressourcen-ID einer Quelle
     * @throws InvalidQueryException wenn die Adresse fehlt, zu lang ist oder ein fehlerhaftes Lesezeichen ist
     */
    public Resolution resolve(AccessContext caller, String address) {
        if (address == null || address.isBlank() || address.length() > PageBookmark.maxAddressLength(address)) {
            throw new InvalidQueryException("address fehlt oder ist zu lang");
        }
        String value = normalize(address);
        Optional<PageBookmark> bookmark;
        try {
            bookmark = PageBookmark.parse(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException(e.getMessage());
        }
        List<HarvestSource> candidates = bookmark.isPresent()
                ? catalog.find(bookmark.get().sourceId()).stream().toList()
                : catalog.all();
        List<Match> matches = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (HarvestSource source : candidates) {
            if (!access.permits(caller, ResourceScope.source(source.id()), AccessKind.CONTENT)) {
                continue;
            }
            try {
                DeliveredPage page = pages.pageByAddress(caller, source.id(), value, false, false);
                matches.add(Match.of(page.header()));
            } catch (CacheMissException | InvalidQueryException | AccessDeniedException
                     | UnknownSourceException e) {
                // Keine (sichtbare) Seite dieser Quelle.
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.UNAVAILABLE
                        || e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
                    unavailable.add(source.id());
                }
            }
        }
        return new Resolution(matches, unavailable);
    }

    /** Unsichtbare Zeichen, die beim Kopieren aus Mails, Chats oder Office-Dokumenten in Links geraten. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u00AD\\u200B-\\u200D\\u2060\\uFEFF]");
    /** Bindestrich-Varianten (geschützt, Gedanken-, Minus-, Vollbreiten-Strich), die im Hostnamen „-“ meinen. */
    private static final Pattern DASHES = Pattern.compile("[\\u2010-\\u2015\\u2212\\uFE58\\uFE63\\uFF0D]");
    private static final Pattern AUTHORITY = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/?#]*)(.*)$",
            Pattern.DOTALL);

    /**
     * Macht eine eingegebene Adresse vergleichbar, ohne ihre Bedeutung zu ändern: Leerraum außen und unsichtbare
     * Zeichen (weiches Trennzeichen, Null-Breite) fallen weg; im Hostnamen werden Unicode-Bindestriche wie der
     * geschützte Bindestrich (U+2011, entsteht beim Kopieren aus Office/Chats) zu „-“ und ein Name mit Umlauten zur
     * ASCII-Form (IDN), damit er mit der Basis-URL einer Quelle übereinstimmt. Pfad, Titel und %-Kodierung bleiben
     * unverändert; die Quelle dekodiert sie selbst.
     *
     * @return die bereinigte Adresse; {@code null} bleibt {@code null}
     */
    public static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String value = INVISIBLE.matcher(address).replaceAll("").strip();
        Matcher parts = AUTHORITY.matcher(value);
        if (!parts.matches()) {
            return value;
        }
        String authority = DASHES.matcher(parts.group(2)).replaceAll("-");
        int at = authority.lastIndexOf('@');
        String user = at < 0 ? "" : authority.substring(0, at + 1);
        String hostPort = authority.substring(at + 1);
        int colon = hostPort.startsWith("[") ? -1 : hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        String port = colon < 0 ? "" : hostPort.substring(colon);
        if (!host.chars().allMatch(c -> c < 0x80)) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
            } catch (IllegalArgumentException malformed) {
                // Bleibt, wie eingegeben; die Quelle erkennt die Adresse dann nicht.
            }
        }
        return parts.group(1) + user + host + port + parts.group(3);
    }

    /**
     * @param matches            die Seiten, auf die die Adresse zeigt, je Quelle höchstens eine, in Quellenreihenfolge
     * @param unavailableSources Quellen, deren Muster passt, die aber für die Auflösung nicht erreichbar waren
     */
    public record Resolution(List<Match> matches, List<String> unavailableSources) {

        public Resolution {
            matches = List.copyOf(matches);
            unavailableSources = List.copyOf(unavailableSources);
        }
    }

    /**
     * Eine aufgelöste Seite.
     *
     * @param bookmark die stabile Ressourcen-ID mit der aktuellen Version
     */
    public record Match(String sourceId, String pageId, String spaceKey, String title, String version, URI url,
                        PageBookmark bookmark) {

        static Match of(PageHeader header) {
            return new Match(header.key().sourceId(), header.key().pageId(), header.metadata().partitionKey(),
                    header.metadata().title(), header.metadata().version(), header.metadata().url(),
                    PageBookmark.of(header));
        }
    }
}
