package com.aresstack.spaceharvester.app.ui.theme;

import com.aresstack.enterpriseai.ui.comic.theme.AskAiTheme;
import com.aresstack.enterpriseai.ui.comic.theme.ThemeColor;

/**
 * Der eine Zugang der App zu ihren Themefarben: {@code AppColors.of(schluessel)} liefert die lebende Farbe
 * des Schlüssels im aktiven Theme ({@link ThemeColor}). Beim ersten Zugriff trägt die Klasse die App-Token in die
 * Farbtabelle der comic-controls ein, damit jede Stelle ihren Schlüssel kennt, egal welche Klasse zuerst lädt.
 */
public final class AppColors {

    static {
        AskAiTheme.registerTokens(AskAiTheme.CLASSIC, AppColorTokens.classic());
    }

    private AppColors() {
    }

    /** Die lebende Farbe des App-Tokens {@code key}; ein unbekannter Schlüssel wirft sofort. */
    public static ThemeColor of(String key) {
        return ThemeColor.of(key);
    }
}
