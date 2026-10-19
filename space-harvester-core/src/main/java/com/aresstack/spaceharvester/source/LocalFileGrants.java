package com.aresstack.spaceharvester.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Freigegebene Ordner für lokale Dateien wie {@code LocalFileAccessPolicy} im Enterprise AI Client (#187): Gelesen wird
 * nur unterhalb des Verzeichnisses einer Quelle und dieser Ordner, mit {@code allowAll} alles, was das Konto des
 * Dienstes lesen darf. Verglichen wird der wirkliche Ort; ein Link aus dem Verzeichnis hinaus führt nur in einen
 * freigegebenen Ordner. Eine Freigabe liest nichts ein, eingelesen wird nur, was eine Quelle nennt.
 *
 * @param allowAll ob alles freigegeben ist, was das Konto lesen darf
 * @param roots    weitere freigegebene Ordner
 */
public record LocalFileGrants(boolean allowAll, List<Path> roots) {

    public LocalFileGrants {
        roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
    }

    /** Nur das Verzeichnis der Quelle selbst, wie bisher. */
    public static LocalFileGrants none() {
        return new LocalFileGrants(false, List.of());
    }
}
