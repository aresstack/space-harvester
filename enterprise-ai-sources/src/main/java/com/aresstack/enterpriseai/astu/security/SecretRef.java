package com.aresstack.enterpriseai.astu.security;

/**
 * Opaker, nicht geheimer Verweis auf ein Secret hinter dem Security-Port, z. B. {@code keepass:confluence-prod}.
 *
 * <p>Ein {@code SecretRef} ist kein Secret: Konfiguration, Use Cases und Logs dürfen ihn tragen und anzeigen.
 * Nur ein Security-Adapter löst ihn in Secret-Material auf; was der Bezeichner im Backend bedeutet (z. B. der
 * Titel eines KeePass-Eintrags), entscheidet allein dieser Adapter.
 *
 * <p>Übernommen aus corenth {@code adyton.SecretRef}; anders als dort ist {@link #toString()} bewusst lesbar,
 * weil der Auftrag (AP13) das Loggen von Referenzen ausdrücklich erlaubt.
 */
public final class SecretRef {

    private final String id;

    private SecretRef(String id) {
        this.id = id;
    }

    /**
     * @param id nicht leerer Bezeichner ohne Steuerzeichen (auch nicht am Rand); Leerzeichen am Rand werden entfernt
     * @throws IllegalArgumentException bei {@code null}, leerem Wert oder Steuerzeichen
     */
    public static SecretRef of(String id) {
        if (id == null) {
            throw new IllegalArgumentException("SecretRef-ID darf nicht null sein");
        }
        for (int i = 0; i < id.length(); i++) {
            if (Character.isISOControl(id.charAt(i))) {
                throw new IllegalArgumentException("SecretRef-ID darf keine Steuerzeichen enthalten");
            }
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("SecretRef-ID darf nicht leer sein");
        }
        return new SecretRef(trimmed);
    }

    /** Der opake Bezeichner. */
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SecretRef && id.equals(((SecretRef) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Lesbar und loggbar: enthält nur den Bezeichner, nie Secret-Material. */
    @Override
    public String toString() {
        return "SecretRef[" + id + "]";
    }
}
