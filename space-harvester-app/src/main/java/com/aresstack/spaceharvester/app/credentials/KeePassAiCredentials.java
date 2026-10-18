package com.aresstack.spaceharvester.app.credentials;

import com.aresstack.enterpriseai.adyton.SecretUnavailableException;
import com.aresstack.enterpriseai.adyton.SecretVault;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Der API-Key aus dem Passwortfeld eines KeePass-Eintrags, wie im Enterprise AI Client. */
public final class KeePassAiCredentials implements AiCredentials {

    private static final Logger LOG = LoggerFactory.getLogger(KeePassAiCredentials.class);

    private final SecretVault keePass;
    private final String entry;

    public KeePassAiCredentials(SecretVault keePass, String entry) {
        this.keePass = keePass;
        this.entry = entry;
    }

    @Override
    public char[] apiKey() {
        try (VaultEntryDraft read = keePass.read(entry)) {
            char[] key = read.copyPassword();
            return key.length == 0 ? null : key;
        } catch (SecretUnavailableException | RuntimeException e) {
            // Ohne KeePass bleibt die semantische Suche aus; der Grund steht im Log, nie ein Wert.
            LOG.warn("Der API-Key des KI-Dienstes ließ sich nicht aus KeePass lesen ({}): {}", entry, e.getMessage());
            return null;
        }
    }
}
