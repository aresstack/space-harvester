package com.aresstack.spaceharvester.app.credentials;

import java.util.function.Supplier;

/** Der API-Key aus Sitzung oder Tresor (DPAPI), wie bisher unter „Manuell“. */
public final class ManualAiCredentials implements AiCredentials {

    private final Supplier<char[]> stored;

    /** @param stored liefert den Key aus Sitzung oder Tresor, {@code null} ohne */
    public ManualAiCredentials(Supplier<char[]> stored) {
        this.stored = stored;
    }

    @Override
    public char[] apiKey() {
        return stored.get();
    }
}
