package com.aresstack.enterpriseai.adyton;

/**
 * Verwendung von {@link SecretMaterial} innerhalb von {@link SecretProvider#withSecret}. Das Material ist nur
 * während des Aufrufs gültig und darf nicht gespeichert werden.
 *
 * @param <T> Ergebnis der Verwendung, z. B. die Antwort eines authentifizierten Aufrufs; darf selbst kein
 *            Secret-Material sein
 * @param <X> Fehler, den die Verwendung selbst werfen darf (z. B. {@code IOException} des Transports)
 */
public interface SecretFunction<T, X extends Exception> {

    T apply(SecretMaterial material) throws X;
}
