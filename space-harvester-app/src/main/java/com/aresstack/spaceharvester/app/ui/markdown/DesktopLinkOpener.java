package com.aresstack.spaceharvester.app.ui.markdown;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

/** Öffnet Links über die Desktop-Integration und kapselt plattformspezifisches Verhalten (aus askai-java8). */
public interface DesktopLinkOpener {

    void open(String url);

    static DesktopLinkOpener systemDefault() {
        return new DesktopLinkOpener() {
            @Override
            public void open(String url) {
                if (url == null || url.trim().isEmpty()) {
                    return;
                }
                try {
                    URI uri = new URI(url);
                    String scheme = uri.getScheme();
                    if (!isAllowedScheme(scheme) || !Desktop.isDesktopSupported()) {
                        return;
                    }
                    Desktop desktop = Desktop.getDesktop();
                    if (isMailScheme(scheme) && desktop.isSupported(Desktop.Action.MAIL)) {
                        desktop.mail(uri); // mailto gehört zum Mailprogramm, nicht zum Browser
                    } else if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        desktop.browse(uri);
                    }
                } catch (Exception ignored) {
                    // Fehler der Desktop-Integration ignorieren, der Chat bleibt benutzbar.
                }
            }
        };
    }

    static boolean isMailScheme(String scheme) {
        return scheme != null && "mailto".equals(scheme.toLowerCase(Locale.ENGLISH));
    }

    static boolean isAllowedScheme(String scheme) {
        if (scheme == null) {
            return false;
        }
        String normalized = scheme.toLowerCase(Locale.ENGLISH);
        return "http".equals(normalized)
                || "https".equals(normalized)
                || "mailto".equals(normalized);
    }
}
