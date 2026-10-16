package com.aresstack.enterpriseai.source.api.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Where the application may read local files and network drives (issue #120): only below the folders the user granted,
 * or everywhere the user account can read when the user explicitly opted into {@link #allowAll()}. Granting decides
 * what may be read, never what gets indexed: indexing stays with the configured knowledge sources.
 *
 * <p>Paths are compared by their real location: symbolic links, junctions and {@code ..} are resolved before the check,
 * so a link inside a granted folder that points outside of every granted folder does not widen the grant. A path whose
 * real location cannot be determined (neither it nor any parent exists) is refused. Mapped drives and UNC paths
 * ({@code \\server\share\folder}) are ordinary paths here; letter case follows the file system.
 */
public final class LocalFileAccessPolicy {

    private final boolean allowAll;
    private final List<Path> roots;

    private LocalFileAccessPolicy(boolean allowAll, List<Path> roots) {
        this.allowAll = allowAll;
        this.roots = Collections.unmodifiableList(roots);
    }

    /** No grant at all: every path is refused. */
    public static LocalFileAccessPolicy none() {
        return new LocalFileAccessPolicy(false, Collections.<Path>emptyList());
    }

    /**
     * @param allowAll whether the user opted into reading everything the user account can read
     * @param roots    the granted folders; {@code null} entries are ignored
     */
    public static LocalFileAccessPolicy of(boolean allowAll, Collection<Path> roots) {
        List<Path> normalized = new ArrayList<Path>();
        if (roots != null) {
            for (Path root : roots) {
                if (root != null) {
                    Path absolute = root.toAbsolutePath().normalize();
                    if (!normalized.contains(absolute)) {
                        normalized.add(absolute);
                    }
                }
            }
        }
        return new LocalFileAccessPolicy(allowAll, normalized);
    }

    /** Whether the user opted into reading every path the user account can read. */
    public boolean allowAll() {
        return allowAll;
    }

    /** The granted folders, absolute and normalized, in grant order. */
    public List<Path> roots() {
        return roots;
    }

    /** This policy plus one more granted folder (a configured local files source grants its own folder). */
    public LocalFileAccessPolicy including(Path root) {
        if (root == null) {
            return this;
        }
        List<Path> more = new ArrayList<Path>(roots);
        more.add(root);
        return of(allowAll, more);
    }

    /** Whether the path may be read: its real location lies in a granted folder, or everything is granted. */
    public boolean isAllowed(Path path) {
        if (path == null) {
            return false;
        }
        if (allowAll) {
            return true;
        }
        Path real = realLocation(path);
        if (real == null) {
            return false;
        }
        for (Path root : roots) {
            Path realRoot = realLocation(root);
            if (realRoot != null && real.startsWith(realRoot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The real location of a path that may be read.
     *
     * @throws SecurityException if the path lies outside of every granted folder
     */
    public Path requireAllowed(Path path) {
        if (!isAllowed(path)) {
            throw new SecurityException("Kein Zugriff: " + path + " liegt außerhalb der freigegebenen Ordner");
        }
        Path real = realLocation(path);
        return real == null ? path.toAbsolutePath().normalize() : real;
    }

    /**
     * The real location: the path itself resolved through the file system, or for a path that does not exist (yet)
     * its nearest existing parent resolved plus the remaining names; {@code null} if nothing can be resolved.
     */
    static Path realLocation(Path path) {
        Path absolute;
        try {
            absolute = path.toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }
        try {
            Path real = existing.toRealPath();
            return existing.equals(absolute) ? real : real.resolve(existing.relativize(absolute)).normalize();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "LocalFileAccessPolicy[allowAll=" + allowAll + ", roots=" + roots + "]";
    }
}
