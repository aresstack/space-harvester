package com.aresstack.spaceharvester.app.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassPairingKeyStore;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Die Kopplung gilt für die Sitzung auch dann, wenn die Datei nicht geschrieben werden kann. */
class SessionKeyStoreTest {

    /** Eine Ablage, die nie etwas behält (Schreibfehler). */
    private static class FailingStore implements KeePassPairingKeyStore {
        int clears;

        @Override
        public char[] load() {
            return null;
        }

        @Override
        public void save(char[] sessionKey) {
            Arrays.fill(sessionKey, '\0');
        }

        @Override
        public void clear() {
            clears++;
        }
    }

    @Test
    void keepsTheKeyForTheSessionWhenTheFileFails() {
        FailingStore file = new FailingStore();
        KeePassLink.SessionKeyStore store = new KeePassLink.SessionKeyStore(file, () -> true);
        char[] key = new char[64];
        Arrays.fill(key, 'b'); // Testwert, kein echter Schlüssel

        store.save(key);
        char[] first = store.load();
        Arrays.fill(first, '\0'); // der Provider überschreibt nach Gebrauch

        assertThat(key).as("Eingabe unverändert").containsOnly('b');
        assertThat(store.load()).containsOnly('b').hasSize(64);
        assertThat(store.held()).isTrue();

        store.clear();
        assertThat(store.load()).isNull();
        assertThat(store.held()).isFalse();
        assertThat(file.clears).isEqualTo(1);
    }

    @Test
    void writesNothingWhenTheDirectoryCannotBeRestricted() {
        int[] saves = {0};
        KeePassPairingKeyStore file = new FailingStore() {
            @Override
            public void save(char[] sessionKey) {
                saves[0]++;
            }
        };
        KeePassLink.SessionKeyStore store = new KeePassLink.SessionKeyStore(file, () -> false);
        char[] key = new char[64];
        Arrays.fill(key, 'c'); // Testwert

        store.save(key);

        assertThat(saves[0]).as("kein Klartext in einem offenen Ordner").isZero();
        assertThat(store.load()).containsOnly('c');
    }
}
