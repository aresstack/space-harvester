package com.aresstack.enterpriseai.embedding.openai;

/**
 * Liefert das Bearer-Token für einen Request. Wird je Request aufgerufen, damit rotierte Tokens ohne Neubau des
 * Adapters greifen und das Token nicht länger als nötig im Speicher liegt. Die Composition Root verdrahtet hier den
 * Security-Port; der Adapter selbst kennt weder KeePass noch Settings.
 */
public interface BearerTokenSource {

    /**
     * @return das Token oder {@code null} bzw. leer für Requests ohne {@code Authorization}-Header. Der Adapter
     *         überschreibt das Array nach Gebrauch mit Nullen.
     */
    char[] bearerToken();
}
