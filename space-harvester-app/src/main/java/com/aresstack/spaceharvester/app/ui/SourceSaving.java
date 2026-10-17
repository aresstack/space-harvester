package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.SecretVault;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Speichern einer Quelle, gemeinsam für den Assistenten und die kleinen Dialoge an den Einstellungen: Datei
 * (kommentarschonend, {@code .bak} nur ohne Klartext-Geheimnis), Tresor und Sitzungswerte in derselben Reihenfolge.
 * Passwort und Token kommen nie in die Datei.
 */
final class SourceSaving {

    private SourceSaving() {
    }

    /**
     * Schreibt {@code draft} (Dateitext mit der Quelle) und legt ein eingegebenes Geheimnis im Tresor ab bzw. entfernt
     * ein unpassend gewordenes. Läuft im Hintergrund.
     *
     * @param editing bisheriger Eintrag oder {@code null} für eine neue Quelle
     * @param value   eingegebenes Token oder Passwort, leer zum Behalten; wird nicht gelöscht
     */
    static void store(AppContext context, ConfigFileEditor editor, SourceDraft.Editable editing, SourceDraft source,
                      ConfigFileEditor.Draft draft, char[] value) throws IOException {
        // Ob die bisherige Fassung ein Geheimnis enthält, entscheidet der Rohtext der ganzen Datei.
        boolean backup = editing == null || !editor.fileHasPlaintextSecret(context.configFile());
        SecretVault vault = context.vault();
        boolean typed = value.length > 0 && source.authType() != SourceSettingsConfig.AuthType.NONE;
        boolean drop = !typed && (loginChanged(editing, source)
                || source.authType() == SourceSettingsConfig.AuthType.NONE);
        if ((typed && vault.available()) || drop) {
            // Erst der Tresor, dann die Datei: Der bisherige Eintrag wird verschlüsselt gemerkt (ohne ihn zu
            // entschlüsseln) und kommt zurück, wenn die Datei scheitert; ein zweiter Versuch beginnt auf
            // demselben Stand.
            String stored = vault.raw(source.id());
            if (typed) {
                vault.store(source.id(), login(source), Arrays.copyOf(value, value.length));
            } else {
                vault.remove(source.id());
            }
            try {
                editor.save(context.configFile(), draft, backup);
            } catch (IOException | RuntimeException e) {
                restore(vault, source.id(), stored, e);
                throw e;
            }
        } else {
            editor.save(context.configFile(), draft, backup);
        }
    }

    /** Nach dem Speichern auf dem EDT: Sitzungswert setzen oder verwerfen. */
    static void remember(AppContext context, SourceDraft.Editable editing, SourceDraft source, char[] value) {
        if (value.length > 0 && source.authType() != SourceSettingsConfig.AuthType.NONE) {
            context.secrets().put(source.id(), login(source), value);
        } else if (loginChanged(editing, source)) {
            // Ein Token taugt nicht als Passwort (und umgekehrt): Mit der Anmeldeart verfällt der Sitzungswert.
            context.secrets().remove(source.id());
        }
    }

    static String login(SourceDraft source) {
        return AppRuntime.SessionSecrets.login(source.authType(), source.username(), source.secretVariable());
    }

    /** Ob Anmeldeart oder Variable beim Bearbeiten wechseln; dann passt ein Sitzungswert nicht mehr. */
    static boolean loginChanged(SourceDraft.Editable editing, SourceDraft source) {
        return editing != null && (editing.draft().authType() != source.authType()
                || !Objects.equals(editing.draft().secretVariable(), source.secretVariable())
                || !login(editing.draft()).equals(login(source)));
    }

    /** Ob für Token bzw. Passwort schon ein Wert da ist: eingegeben, in der Sitzung, im Tresor oder als Property. */
    static boolean secretAvailable(AppContext context, SourceDraft.Editable editing, SourceDraft source,
                                   boolean typed) {
        if (source.authType() == SourceSettingsConfig.AuthType.NONE || typed) {
            return true;
        }
        String sourceId = source.id();
        // Nur ein Wert für genau diese Anmeldung zählt, auch wenn die Datei von Hand umgestellt wurde.
        if (sourceId != null && !loginChanged(editing, source) && (context.secrets().has(sourceId, login(source))
                || context.vault().has(sourceId, login(source)))) {
            return true;
        }
        String name = source.secretVariable();
        return name != null && (System.getProperty(name) != null || System.getenv(name) != null);
    }

    private static void restore(SecretVault vault, String sourceId, String stored, Exception failure) {
        try {
            if (stored == null) {
                vault.remove(sourceId);
            } else {
                vault.restore(sourceId, stored);
            }
        } catch (IOException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }
}
