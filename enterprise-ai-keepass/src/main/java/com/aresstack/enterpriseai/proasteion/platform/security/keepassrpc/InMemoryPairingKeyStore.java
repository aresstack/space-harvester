package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import java.util.Arrays;

/**
 * Hält den Pairing-Schlüssel nur im Speicher dieses Prozesses: Nach jedem Neustart ist ein neues Pairing nötig.
 * Sicherer Default, solange die Composition Root keine geschützte persistente Ablage verdrahtet.
 */
public final class InMemoryPairingKeyStore implements KeePassPairingKeyStore {

    private char[] key;

    @Override
    public synchronized char[] load() {
        return key == null ? null : Arrays.copyOf(key, key.length);
    }

    @Override
    public synchronized void save(char[] sessionKey) {
        clear();
        key = sessionKey == null ? null : Arrays.copyOf(sessionKey, sessionKey.length);
    }

    @Override
    public synchronized void clear() {
        if (key != null) {
            Arrays.fill(key, '\0');
            key = null;
        }
    }

    @Override
    public String toString() {
        return "InMemoryPairingKeyStore[***]";
    }
}
