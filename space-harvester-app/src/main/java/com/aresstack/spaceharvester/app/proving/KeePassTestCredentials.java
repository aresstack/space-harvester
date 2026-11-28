package com.aresstack.spaceharvester.app.proving;

import com.aresstack.enterpriseai.adyton.VaultEntryDraft;
import com.aresstack.spaceharvester.app.credentials.KeePassLink;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.aresstack.spaceharvester.provingground.run.Credentials;
import com.aresstack.spaceharvester.provingground.run.UserCredentials;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Zugangsdaten der Testbenutzer aus KeePass (Proving Ground, #1153): der KeePass-Eintrag des Benutzers
 * ({@link TestUser#vaultEntry()}) liefert Login und Passwort, ein Zusatzfeld das OTP-Geheimnis. Über dieselbe Kopplung
 * wie das Command Deck und immer still (kein Kopplungsdialog mitten im Testlauf). Gelesen wird erst, wenn ein Schritt
 * den Wert braucht, und nichts davon wird gespeichert; nur der Login (kein Geheimnis) bleibt für einen Testlauf im
 * Speicher. Darum je Testlauf eine neue Instanz.
 */
public final class KeePassTestCredentials implements Credentials {

    static final String NOT_PAIRED = "KeePass ist nicht gekoppelt – Einstellungen › Sicherheit › KeePass";

    /** Felder, in denen KeePass und KeePassXC das OTP-Geheimnis ablegen (Base32 oder otpauth://-URI). */
    static final List<String> OTP_FIELDS = List.of("otp", "TimeOtp-Secret-Base32", "TOTP Seed", "TOTP-Seed");

    private final Supplier<KeePassLink> link;
    private final Map<String, UserCredentials> users = new java.util.concurrent.ConcurrentHashMap<>();

    public KeePassTestCredentials(Supplier<KeePassLink> link) {
        this.link = link;
    }

    @Override
    public UserCredentials of(TestUser user) {
        if (user.vaultEntry().isEmpty()) {
            return null;
        }
        String entry = user.vaultEntry();
        return users.computeIfAbsent(user.name(), name -> new UserCredentials(read(entry, VaultEntryDraft::userName),
                () -> read(entry, KeePassTestCredentials::password),
                () -> read(entry, draft -> otp(draft.fields()))));
    }

    private interface Field {
        String of(VaultEntryDraft draft);
    }

    private String read(String entry, Field field) {
        KeePassLink keePass = link.get();
        if (keePass == null || !keePass.paired()) {
            throw new IllegalStateException(NOT_PAIRED);
        }
        try (VaultEntryDraft draft = keePass.readSilently(entry)) {
            return field.of(draft);
        } catch (RuntimeException failed) {
            throw failed;
        } catch (Exception failed) {
            throw new IllegalStateException("KeePass-Eintrag " + entry + ": " + failed.getMessage(), failed);
        }
    }

    private static String password(VaultEntryDraft draft) {
        char[] password = draft.copyPassword();
        try {
            return password == null ? null : new String(password);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** Das OTP-Geheimnis aus den Zusatzfeldern des Eintrags, oder null. */
    static String otp(Map<String, String> fields) {
        if (fields == null) {
            return null;
        }
        for (String name : OTP_FIELDS) {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (field.getKey() != null && field.getKey().equalsIgnoreCase(name)
                        && field.getValue() != null && !field.getValue().isBlank()) {
                    return field.getValue();
                }
            }
        }
        return null;
    }
}
