package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Die Browser-Regeln des Systemhinweises wortgleich aus {@code ToolUsePolicy} des Enterprise AI Client: wie der
 * Browser mehrere Seiten liest ({@link #BROWSER}) und welches Profil mit welchen Such-URLs gilt
 * ({@link #browserProfile}). Der Rest von {@code ToolUsePolicy} (Antwortplan, Programmhilfe, Recherche) gehört nicht
 * hierher; die interne Suche hat ihre eigene Regel ohne Vorab-Entscheidung.
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
     * Die Regel des Browserprofils eines Chats: nennt das Profil und seine Such-URL-Vorlagen für eine freie
     * Websuche.
     *
     * @param profile    Name des aktiven Profils (Intern oder Extern)
     * @param searchUrls Such-URL-Vorlagen mit {@code {query}}, in Reihenfolge; leer gibt eine Regel ohne Such-URLs
     * @param webSearch  ob der Chat {@code browser_search} hat (Profil Extern, ADR 0046)
     */
    public static String browserProfile(String profile, Collection<String> searchUrls, boolean webSearch) {
        StringBuilder text = new StringBuilder("[Browserprofil] Aktives Browserprofil: ").append(profile)
                .append(". Die Freigaben (Whitelist/Blacklist) dieses Profils gelten für jede Adresse.");
        List<String> urls = new ArrayList<>();
        if (searchUrls != null) {
            for (String url : searchUrls) {
                if (url != null && !url.trim().isEmpty()) {
                    urls.add(url.trim());
                }
            }
        }
        if (webSearch) {
            text.append(" Braucht die Aufgabe eine freie Websuche und nennt keine Einstiegsseite, suche mit "
                    + BrowserMcpTools.SEARCH + " und öffne die passenden Treffer. Nennt die Aufgabe eine Adresse, "
                    + "öffne sie mit " + BrowserMcpTools.NAVIGATE + " und nutze die Suche dieser Website.");
        }
        if (urls.isEmpty()) {
            text.append(webSearch ? "" : " Für dieses Profil sind keine Such-URLs eingestellt: nutze die Adresse aus "
                    + "der Aufgabe und die Suche der Website.");
            return text.toString();
        }
        text.append(" Such-URLs dieses Profils:");
        for (String url : urls) {
            text.append(' ').append(url);
        }
        text.append(webSearch ? ". Diese URLs nutzt du, wenn " + BrowserMcpTools.SEARCH + " nichts Passendes findet: "
                + "öffne sie mit " + BrowserMcpTools.NAVIGATE + " und ersetze {query} durch die URL-kodierten "
                + "Suchbegriffe." : ". Braucht die Aufgabe eine freie Websuche und nennt keine Einstiegsseite, öffne "
                + "eine dieser URLs mit " + BrowserMcpTools.NAVIGATE + " und ersetze {query} durch die URL-kodierten "
                + "Suchbegriffe; die erste ist die bevorzugte. Kennst du die Website schon, nutze deren eigene Suche.");
        return text.toString();
    }
}
