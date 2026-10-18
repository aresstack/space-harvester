package com.aresstack.spaceharvester.app.credentials;

/**
 * Austauschbare Herkunft des API-Keys des KI-Dienstes in der Desktop-App: {@link ManualAiCredentials} (Tresor dieses
 * Windows-Kontos bzw. Sitzung) oder {@link KeePassAiCredentials}. Beide liefern denselben aufgelösten Key an die
 * Laufzeit; der Core kennt keine dieser Quellen.
 */
public interface AiCredentials {

    /** Der API-Key oder {@code null}; liest womöglich Tresor oder KeePass, also nur im Hintergrund. Der Aufrufer leert ihn. */
    char[] apiKey();
}
