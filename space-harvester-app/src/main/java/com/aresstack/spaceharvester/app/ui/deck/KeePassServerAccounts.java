package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.adyton.VaultEntry;
import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import java.util.List;
import java.util.function.Supplier;

/**
 * Host-Zugänge des Command Deck aus KeePass, über dieselbe Kopplung wie die Wissensquellen (Einstellungen ›
 * Sicherheit › KeePass). Die Liste wird nur gelesen, wenn schon gekoppelt ist; einen Kopplungsdialog öffnet das
 * Deck nie von selbst.
 */
public final class KeePassServerAccounts implements ServerAccounts {

    static final String NOT_PAIRED = "KeePass ist nicht gekoppelt – Einstellungen › Sicherheit › KeePass";

    private final Supplier<KeePassLink> link;

    public KeePassServerAccounts(Supplier<KeePassLink> link) {
        this.link = link;
    }

    @Override
    public List<ServerAccount> accounts() throws Exception {
        KeePassLink keePass = paired();
        return keePass.entries().stream()
                .filter(entry -> entry.title() != null && !entry.title().isBlank())
                .map(KeePassServerAccounts::account)
                .toList();
    }

    @Override
    public ServerCredentials credentials(String entry) throws Exception {
        try (VaultEntryDraft draft = paired().read(entry)) {
            char[] password = draft.copyPassword();
            try {
                return new ServerCredentials(new ServerAccount(draft.title(), draft.location(), draft.userName()),
                        password);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        }
    }

    private KeePassLink paired() {
        KeePassLink keePass = link.get();
        if (keePass == null || !keePass.paired()) {
            throw new IllegalStateException(NOT_PAIRED);
        }
        return keePass;
    }

    static ServerAccount account(VaultEntry entry) {
        return new ServerAccount(entry.title(), entry.location(), entry.userName());
    }
}
