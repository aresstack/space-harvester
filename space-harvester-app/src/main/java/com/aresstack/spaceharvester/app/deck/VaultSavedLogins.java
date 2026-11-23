package com.aresstack.spaceharvester.app.deck;

import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins;
import java.util.Locale;
import java.util.Optional;

/**
 * SharePoint-Zugangsdaten im Tresor der App (unter Windows DPAPI für das angemeldete Konto, Unterordner
 * {@code sharepoint}); der Benutzername steht im App-Zustand, das Kennwort nur im Tresor. Ohne Tresor gelten die Daten
 * bis zum Schließen der App.
 */
public final class VaultSavedLogins implements SavedLogins {

    static final String USER_KEY = "deck.sharepoint.user.";

    private final SecretVault vault;
    private final SavedLogins fallback = SavedLogins.inMemory();

    public VaultSavedLogins(SecretVault vault) {
        this.vault = vault;
    }

    @Override
    public Optional<Login> load(String host) throws Exception {
        if (vault == null || !vault.available()) {
            return fallback.load(host);
        }
        String id = id(host);
        String user = AppState.shared().get(USER_KEY + id);
        if (user == null) {
            return Optional.empty();
        }
        char[] password = vault.load(id, user);
        return password == null ? Optional.empty() : Optional.of(new Login(user, password));
    }

    @Override
    public void save(String host, String user, char[] password) throws Exception {
        if (vault == null || !vault.available()) {
            fallback.save(host, user, password);
            return;
        }
        String id = id(host);
        // store() überschreibt seinen Wert danach; das Kennwort des Aufrufers bleibt.
        vault.store(id, user, password.clone());
        AppState.shared().put(USER_KEY + id, user);
    }

    /** Dateiname im Tresor: {@code sp-} und der Rechner in Kleinbuchstaben, nur Buchstaben, Ziffern, Bindestrich. */
    static String id(String host) {
        String cleaned = host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String id = "sp-" + cleaned;
        return id.length() > 63 ? id.substring(0, 63) : id;
    }
}
