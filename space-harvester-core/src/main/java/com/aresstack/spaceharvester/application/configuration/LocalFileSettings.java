package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.source.LocalFileGrants;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Freigegebene Ordner für lokale Dateien (#187) unter {@code space-harvester.local-files}, wie
 * {@code FileAccessConfig} im Enterprise AI Client ({@code files.access.allowAll}, {@code files.access.roots}):
 * Standard ist kein Zugriff über das Verzeichnis einer Quelle hinaus. Gilt ohne Neustart; der Adapter liest die
 * Freigaben bei jedem Dateizugriff ({@link LocalFileGrants}).
 *
 * <p>Wie im Client gewährt ein Ordner, den es (noch) nicht gibt, nichts, bis er da ist, etwa ein gemapptes oder
 * UNC-Laufwerk, das später verbunden wird. {@link HarvesterConfiguration#validate()} verlangt absolute Pfade und lehnt
 * einen vorhandenen Eintrag ab, der kein Verzeichnis ist.
 *
 * @param allowAll ob alles freigegeben ist, was das Konto des Dienstes lesen darf
 * @param roots    weitere freigegebene Ordner, normalisiert und ohne Doppelte
 */
public record LocalFileSettings(boolean allowAll, List<Path> roots) {

    /** {@code local-files.allow-all}: {@code true} oder {@code false}. */
    public static final String ALLOW_ALL = "local-files.allow-all";
    /** {@code local-files.roots}: Ordner, getrennt durch {@code ;} wie im Client, oder als Liste. */
    public static final String ROOTS = "local-files.roots";
    /** Trenner der Ordner in einem Text, wie {@code FileAccessConfig}; Kommas kommen in Pfaden vor. */
    public static final String SEPARATOR = ";";

    /** Nur das Verzeichnis der Quelle selbst. */
    public static final LocalFileSettings DEFAULT = new LocalFileSettings(false, List.of());

    public LocalFileSettings {
        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (Path root : Objects.requireNonNull(roots, "roots")) {
            unique.add(Objects.requireNonNull(root, "root").normalize());
        }
        roots = List.copyOf(unique);
    }

    /** Die Freigaben für den Adapter der Quellenart {@code files}. */
    public LocalFileGrants grants() {
        return new LocalFileGrants(allowAll, roots);
    }
}
