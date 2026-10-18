package com.aresstack.spaceharvester.source;

import com.aresstack.spaceharvester.application.sources.ConnectionStep.Stage;
import com.aresstack.spaceharvester.application.sources.ConnectionStep.Status;
import com.aresstack.spaceharvester.application.sources.ConnectionStep;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceDiagnoses;
import com.aresstack.spaceharvester.application.sources.SourceDiagnosis;
import com.aresstack.spaceharvester.source.network.ConnectionDiagnosis;
import com.aresstack.spaceharvester.source.network.ConnectionProbe;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verbindungsdiagnose einer Quelle in Stufen (#189), wie {@code ConnectionProbe} und {@code ConnectionDiagnosis} im
 * Enterprise AI Client: Proxy-Route, Namensauflösung, TCP (bei Proxy samt {@code CONNECT}-Tunnel und
 * Proxy-Anmeldung), TLS mit dem Vertrauen der Quelle, dann Anmeldung und API über den echten Quellenadapter (die
 * sichtbaren Spaces live, ohne etwas abzulegen). Ein gescheiterter Schritt nennt Ursache und Hinweis; die folgenden
 * erscheinen als nicht geprüft. Meldungen nennen nie Zugangsdaten.
 *
 * <p>Rechte: Die Diagnose prüft keine {@code AccessPolicy}; sie gehört dem Betreiber, wie die Quellenverwaltung.
 */
public final class SourceDiagnostics implements SourceDiagnoses {

    /** Frist je Netzstufe. */
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

    private static final Logger LOG = LoggerFactory.getLogger(SourceDiagnostics.class);
    private static final String NOT_CHECKED = "nicht geprüft";

    private final ConfiguredSourceCatalog catalog;
    private final Duration timeout;

    public SourceDiagnostics(ConfiguredSourceCatalog catalog) {
        this(catalog, PROBE_TIMEOUT);
    }

    SourceDiagnostics(ConfiguredSourceCatalog catalog, Duration timeout) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Diagnose für vorhandene Einstellungen, auch einer abgeschalteten Quelle.
     *
     * @return leer ohne Quelle dieser ID
     */
    @Override
    public Optional<SourceDiagnosis> diagnose(String sourceId) {
        return catalog.config(sourceId).map(config -> run(config, () -> catalog.probe(sourceId,
                source -> source.partitions()).size()));
    }

    /**
     * Diagnose für Einstellungen, die noch nicht übernommen sind (Dialog).
     *
     * @param replacing {@code true} beim Ändern einer vorhandenen Quelle derselben ID
     * @throws InvalidSourceConfigurationException mit den Problemen der Einstellungen; dann wird nichts versucht
     */
    public SourceDiagnosis diagnose(SourceSettingsConfig config, boolean replacing) {
        List<String> problems = catalog.problems(config, replacing);
        if (!problems.isEmpty()) {
            throw new InvalidSourceConfigurationException(problems);
        }
        return run(config, () -> catalog.probe(config, replacing, source -> source.partitions()).size());
    }

    private SourceDiagnosis run(SourceSettingsConfig config, IntSupplier partitions) {
        List<ConnectionStep> steps = new ArrayList<>();
        URI base = config.baseUrl();
        boolean network = base != null && base.getHost() != null
                && ("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()));
        if (network) {
            X509TrustManager trust;
            try {
                trust = catalog.trustFor(config);
            } catch (IllegalArgumentException e) {
                throw new InvalidSourceConfigurationException(List.of("trust-bundle: " + e.getMessage()));
            }
            boolean reachable = catalog.connectionProbe(timeout).run(base, trust,
                    config.clientCertificate().enabled(), steps::add);
            if (!reachable) {
                steps.add(ConnectionStep.skipped(Stage.AUTHENTICATION, NOT_CHECKED));
                steps.add(ConnectionStep.skipped(Stage.API, NOT_CHECKED));
                return finish(config.id(), steps);
            }
        } else {
            for (Stage stage : List.of(Stage.PROXY_ROUTE, Stage.DNS, Stage.TCP, Stage.TLS)) {
                steps.add(ConnectionStep.info(stage, "keine Netzverbindung für Quellentyp " + config.type()));
            }
        }
        sourceSteps(config, partitions, steps);
        return finish(config.id(), steps);
    }

    private static void sourceSteps(SourceSettingsConfig config, IntSupplier listing, List<ConnectionStep> steps) {
        boolean anonymous = config.auth().type() == SourceSettingsConfig.AuthType.NONE;
        try {
            int partitions = listing.getAsInt();
            steps.add(anonymous ? ConnectionStep.info(Stage.AUTHENTICATION, "ohne Anmeldung (anonym)")
                    : ConnectionStep.ok(Stage.AUTHENTICATION, "angenommen ("
                    + config.auth().type().name().toLowerCase(Locale.ROOT) + ")"));
            steps.add(ConnectionStep.ok(Stage.API, partitions == 1 ? "1 Space sichtbar" : partitions + " Spaces sichtbar"));
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.ACCESS_DENIED) {
                String hint = anonymous ? "Die Quelle verlangt eine Anmeldung: auth (basic oder pat) einstellen."
                        : "Benutzer, Passwort oder Token prüfen; ein Token braucht Leserechte auf die Spaces."
                        + (config.clientCertificate().enabled() ? " Mit mTLS auch das Client-Zertifikat prüfen." : "");
                steps.add(new ConnectionStep(Stage.AUTHENTICATION, Status.FAILED, message(e.reason()), hint));
                steps.add(ConnectionStep.skipped(Stage.API, NOT_CHECKED));
            } else {
                steps.add(ConnectionStep.skipped(Stage.AUTHENTICATION, "nicht feststellbar"));
                steps.add(new ConnectionStep(Stage.API, Status.FAILED, message(e.reason()), ConnectionDiagnosis.hint(e)));
            }
        } catch (InvalidSourceConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            steps.add(ConnectionStep.skipped(Stage.AUTHENTICATION, "nicht feststellbar"));
            steps.add(new ConnectionStep(Stage.API, Status.FAILED, ConnectionDiagnosis.detail(e),
                    ConnectionDiagnosis.hint(e)));
        }
    }

    private static SourceDiagnosis finish(String sourceId, List<ConnectionStep> steps) {
        SourceDiagnosis diagnosis = new SourceDiagnosis(steps);
        if (diagnosis.successful()) {
            LOG.info("Verbindungsdiagnose der Quelle {}: {}", sourceId, diagnosis.summary());
        } else {
            LOG.warn("Verbindungsdiagnose der Quelle {}: {}", sourceId, diagnosis.summary());
        }
        return diagnosis;
    }

    /** Die Art eines Zugriffsfehlers für Benutzer, ohne Werte der Einstellungen. */
    public static String message(SourceAccessException.Reason reason) {
        return switch (reason) {
            case ACCESS_DENIED -> "Anmeldung abgelehnt: Zugangsdaten fehlen, sind falsch oder reichen nicht aus";
            case NOT_FOUND -> "Ziel nicht gefunden: Adresse prüfen";
            case UNAVAILABLE -> "Nicht erreichbar: Netzweg, Proxy oder Zertifikat prüfen";
            case RATE_LIMITED -> "Die Quelle drosselt gerade; später erneut versuchen";
            case UNSUPPORTED -> "Die Quelle kann keine Partitionen auflisten";
            case INVALID_RESPONSE -> "Antwort der Quelle nicht verwertbar: Adresse und Quellentyp prüfen";
        };
    }
}
