package com.aresstack.spaceharvester.application.jobs;

import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * Liest aus der Ausnahmekette eines gescheiterten Auftrags die Befunde für ein {@link JobEvent}: Bereich, Komponente,
 * HTTP-Status, TLS- oder Zertifikatsbefund und die Kette mit Stacktrace als Detail. Alle Texte durchlaufen später
 * {@link Redaction}.
 */
public final class FailureDiagnosis {

    private static final int MAX_CHAIN = 12;
    private static final String PACKAGE = "com.aresstack.";
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)\\bHTTP(?:\\s+status)?\\s*[ :=]?\\s*([1-5][0-9]{2})\\b");
    private static final Set<String> STORAGE_CODES = Set.of("CACHE_UNAVAILABLE", "EXPORT_STORAGE_FULL",
            "GENERATION_CHANGED", "CHANGED_DURING_EXPORT", "INCONSISTENT_READ", "CONVERSION_FAILED",
            "CONVERSION_TOO_LARGE");
    private static final Set<String> ACCESS_CODES = Set.of("ACCESS_REVOKED", "ACCESS_DENIED", "SOURCE_ACCESS_DENIED");

    /**
     * @param category   Bereich des Ereignisses
     * @param component  Klasse, in der der Fehler im Space Harvester auftrat, oder {@code null}
     * @param httpStatus HTTP-Status aus der Kette oder {@code null}
     * @param tlsFinding TLS- oder Zertifikatsbefund oder {@code null}
     * @param network    die Kette enthält einen Verbindungs- oder TLS-Fehler; die Proxy-Route ist dann aufschlussreich
     * @param detail     Kette mit Stacktrace
     */
    public record Findings(JobEvent.Category category, String component, Integer httpStatus, String tlsFinding,
                           boolean network, String detail) {
    }

    private FailureDiagnosis() {
    }

    public static Findings of(Throwable failure, String code) {
        List<Throwable> chain = chain(failure);
        Integer status = null;
        String tls = null;
        boolean network = false;
        boolean source = false;
        for (Throwable cause : chain) {
            if (status == null && cause.getMessage() != null) {
                Matcher matcher = HTTP_STATUS.matcher(cause.getMessage());
                if (matcher.find()) {
                    status = Integer.valueOf(matcher.group(1));
                }
            }
            if (tls == null && (cause instanceof SSLException || cause instanceof GeneralSecurityException)) {
                tls = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
                network = true;
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                network = true;
            }
            if (cause instanceof SourceAccessException access) {
                source = true;
                if (access.reason() == SourceAccessException.Reason.UNAVAILABLE) {
                    network = true;
                }
            }
        }
        JobEvent.Category category;
        if (network) {
            category = JobEvent.Category.NETWORK;
        } else if (code != null && ACCESS_CODES.contains(code)) {
            category = JobEvent.Category.ACCESS;
        } else if (source || status != null) {
            category = JobEvent.Category.SOURCE;
        } else if (code != null && STORAGE_CODES.contains(code)) {
            category = JobEvent.Category.STORAGE;
        } else {
            category = JobEvent.Category.JOB;
        }
        return new Findings(category, component(chain), status, tls, network, stackTrace(failure));
    }

    private static List<Throwable> chain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable cause = failure; cause != null && chain.size() < MAX_CHAIN && seen.put(cause, true) == null;
             cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    /** Die Klasse des Space Harvester, die der Ursache am nächsten ist. */
    private static String component(List<Throwable> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (StackTraceElement frame : chain.get(i).getStackTrace()) {
                if (frame.getClassName().startsWith(PACKAGE)) {
                    String name = frame.getClassName();
                    name = name.substring(name.lastIndexOf('.') + 1);
                    int inner = name.indexOf('$');
                    return inner > 0 ? name.substring(0, inner) : name;
                }
            }
        }
        return null;
    }

    private static String stackTrace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
