package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Welche Startpunkte die letzte Inventur vollständig erfasst hat. Nur dort ist das Fehlen einer Ressource ein Hinweis
 * auf ihre Löschung. Vollständigkeit bemisst sich am tatsächlichen Ergebnis je Startpunkt (HLQ, Dataset, Verzeichnis):
 * Ein eingestelltes {@code maxDepth} oder {@code maxResources} macht einen Startpunkt erst unvollständig, wenn
 * deswegen tatsächlich etwas nicht erfasst wurde.
 */
final class FtpInventoryCoverage {

    private final List<String> completeRoots;
    /** Alle Startpunkte der Inventur; {@code null}, solange keine gelaufen ist (Umfang unbekannt). */
    private final List<String> roots;

    FtpInventoryCoverage(Collection<String> completeRoots, Collection<String> roots) {
        this.completeRoots = new ArrayList<String>(completeRoots);
        this.roots = roots == null ? null : new ArrayList<String>(roots);
    }

    static FtpInventoryCoverage empty() {
        return new FtpInventoryCoverage(Collections.<String>emptyList(), null);
    }

    /**
     * Ob {@code path} unter einem Startpunkt der letzten Inventur liegt, gleich ob vollständig erfasst. Ohne Inventur
     * ist der Umfang unbekannt; dann gilt alles als im Umfang, damit nichts als „außerhalb“ entfernt wird.
     */
    boolean within(String path) {
        if (roots == null) {
            return true;
        }
        for (String root : roots) {
            if (contains(root, path)) {
                return true;
            }
        }
        return false;
    }

    /** Ob {@code path} (ID-Pfad ohne {@code host:port/}) unter einem vollständig erfassten Startpunkt liegt. */
    boolean covers(String path) {
        for (String root : completeRoots) {
            if (contains(root, path)) {
                return true;
            }
        }
        return false;
    }

    static boolean contains(String point, String path) {
        String start = point.trim();
        if (start.isEmpty() || "-".equals(start)) {
            return false;
        }
        if (start.startsWith("/") || start.contains("/") || path.contains("/")) {
            String root = start.replaceFirst("^/+", "").replaceFirst("/+$", "");
            return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
        }
        String root = start.replaceFirst("^'+", "").replaceFirst("'+$", "")
                .replaceFirst("\\.\\*$", "").toUpperCase(Locale.ROOT);
        String member = path.toUpperCase(Locale.ROOT);
        return !root.isEmpty() && (member.equals(root) || member.startsWith(root + ".")
                || member.startsWith(root + "("));
    }
}
