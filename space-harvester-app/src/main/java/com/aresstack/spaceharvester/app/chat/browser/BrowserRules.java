package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.application.config.BrowserProfile;

/**
 * Die Browser-Regeln des Systemhinweises aus {@code ToolUsePolicy} des Enterprise AI Client: wie der Browser mehrere
 * Seiten liest ({@link #BROWSER}) und welches Profil gilt ({@link #browserProfile}). Der Rest von
 * {@code ToolUsePolicy} (Antwortplan, Programmhilfe, Recherche) gehört nicht hierher; die interne Suche hat ihre
 * eigene Regel ohne Vorab-Entscheidung.
 */
public final class BrowserRules {

    public static final String BROWSER = "Im Browser liest du mehr als eine Seite immer parallel mit "
            + BrowserMcpTools.OPEN_MANY + " (Suchergebnisse, Linklisten, Teaser; per links=[Ids], urls oder "
            + "from_page), nicht nacheinander mit " + BrowserMcpTools.FOLLOW + ". Trefferlisten und Teaser zeigen nur "
            + "Ausschnitte: öffne die relevanten Seiten, bevor du antwortest. " + BrowserMcpTools.FOLLOW
            + " nur für eine einzelne Seite oder wenn du auf der Seite etwas bedienen musst; Captcha-, Anmelde- und "
            + "Einwilligungsseiten bleiben einzeln. Gib " + BrowserMcpTools.OPEN_MANY + " dabei question mit, worauf du "
            + "die Seiten prüfst: Dann liest je Seite ein Unteragent gleichzeitig den Volltext und liefert nur ihren "
            + "Beitrag; für Einzelheiten danach die Seite einzeln lesen.";

    private BrowserRules() {
    }

    /**
     * Die Regel des Browserprofils eines Chats: nennt das Profil; gesucht wird mit {@link BrowserMcpTools#SEARCH} in
     * den Suchmaschinen dieses Profils, die Treffer öffnet der Browser des Chats. Intern sind diese Suchmaschinen die
     * Suchseiten des Hauses: Das Modell braucht dafür keine Adresse des Intranets, die Suchseite fragt das Programm
     * selbst beim Benutzer ab, wenn noch keine eingestellt ist. Ohne diesen Satz las das Modell „im Intranet suchen“
     * nicht als Websuche, suchte eine Intranet-Adresse und gab ohne Suche auf.
     */
    public static String browserProfile(BrowserProfile profile) {
        String rules = "[Browserprofil] Aktives Browserprofil: " + profile.label() + ". Die Freigaben "
                + "(Whitelist/Blacklist) dieses Profils gelten für jede Adresse. ";
        if (profile == BrowserProfile.INTERNAL) {
            return rules + BrowserMcpTools.SEARCH + " durchsucht in diesem Profil die Suchseiten des Hauses, also das "
                    + "Intranet; eine Intranet-Adresse oder Zugangsdaten brauchst du dafür nicht (ist noch keine "
                    + "Suchseite eingestellt, fragt das Programm den Benutzer selbst danach). Suche interne Inhalte "
                    + "deshalb immer zuerst mit " + BrowserMcpTools.SEARCH + ", auch wenn die Aufgabe keine Adresse "
                    + "nennt, und öffne die passenden Treffer: Sie sind Seiten des Intranets. Nennt die Aufgabe eine "
                    + "Adresse, öffne sie mit " + BrowserMcpTools.NAVIGATE + " und nutze die Suche dieser Website.";
        }
        return rules + "Braucht die Aufgabe eine freie Websuche und nennt keine Einstiegsseite, suche mit "
                + BrowserMcpTools.SEARCH + " (die Suchmaschinen dieses Profils) und öffne die passenden Treffer. "
                + "Nennt die Aufgabe eine Adresse, öffne sie mit " + BrowserMcpTools.NAVIGATE + " und nutze die Suche "
                + "dieser Website.";
    }
}
