package com.aresstack.enterpriseai.astu.knowledge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stabile, quellenübergreifend eindeutige Identität einer Wissensressource in URI-Form
 * {@code <schema>:<schemaspezifischer Teil>}, z. B. {@code wiki:wiki.intern/w/Hauptseite} oder
 * {@code confluence:cf.intern/confluence/page/123456}. Der schemaspezifische Teil beginnt bei allen Adaptern mit dem
 * kanonischen Endpoint der Quelle ({@code SourceLocators}), nie mit einem Benutzernamen der Quelle.
 *
 * <p>Das Schema benennt die Art der Quelle (klein geschrieben, {@code [a-z][a-z0-9+.-]*}), der Rest wird vom
 * jeweiligen Source-Adapter vergeben und muss für dieselbe Ressource über Läufe hinweg gleich bleiben – er ist
 * der Schlüssel für Upsert, Replace und Delete im Index. Kein Leerraum am Rand, keine Steuerzeichen; Leerzeichen
 * im Inneren (Seitentitel) sind erlaubt. Die ID ist kein Netzwerkort und enthält keine Zugangsdaten; der
 * aufrufbare Ort einer Ressource steht in {@link KnowledgeResource#location()}.
 *
 * <p>Angelehnt an die Resource-Identity von aresstack/corenth ({@code VirtualResourceRef}/{@code BookmarkUri}),
 * hier bewusst auf einen Wert reduziert.
 */
public final class KnowledgeResourceId {

    private static final Pattern FORMAT = Pattern.compile("([a-z][a-z0-9+.-]*):(.+)", Pattern.DOTALL);
    private static final int MAX_LENGTH = 2048;

    private final String value;
    private final String scheme;

    private KnowledgeResourceId(String value, String scheme) {
        this.value = value;
        this.scheme = scheme;
    }

    public static KnowledgeResourceId of(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            throw invalid(value, "fehlt oder ist länger als " + MAX_LENGTH + " Zeichen");
        }
        Matcher matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw invalid(value, "erwartet <schema>:<id> mit klein geschriebenem Schema");
        }
        if (KnowledgeText.hasEdgeSpace(value)) {
            throw invalid(value, "beginnt oder endet mit Leerraum");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw invalid(value, "enthält Steuerzeichen");
            }
        }
        return new KnowledgeResourceId(value, matcher.group(1));
    }

    /** Baut eine ID aus Schema und schemaspezifischem Teil, z. B. {@code of("wiki", "intranet/Hauptseite")}. */
    public static KnowledgeResourceId of(String scheme, String schemeSpecificPart) {
        if (scheme == null || schemeSpecificPart == null) {
            throw invalid(scheme + ":" + schemeSpecificPart, "Schema und schemaspezifischer Teil sind Pflicht");
        }
        return of(scheme + ":" + schemeSpecificPart);
    }

    public String value() {
        return value;
    }

    public String scheme() {
        return scheme;
    }

    public String schemeSpecificPart() {
        return value.substring(scheme.length() + 1);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof KnowledgeResourceId && value.equals(((KnowledgeResourceId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    private static IllegalArgumentException invalid(String value, String reason) {
        return new IllegalArgumentException("Ungültige Knowledge-Resource-ID " + SourceAddress.quote(value)
                + ": " + reason);
    }
}
