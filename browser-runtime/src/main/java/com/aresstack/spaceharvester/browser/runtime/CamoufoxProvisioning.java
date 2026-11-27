package com.aresstack.spaceharvester.browser.runtime;

import com.aresstack.spaceharvester.browser.runtime.camoufox.CamoufoxDistribution;
import com.aresstack.spaceharvester.browser.runtime.camoufox.CamoufoxInstaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Where the runtime finds the pinned, verified Camoufox (moved from Ask AI): in the folder the host configured, as
 * the root that holds {@code <release>-<target>} or as that installation itself. "Installed?" and "install" are
 * separate; a browser start only uses an installed Camoufox and never downloads one (the host asks for the click).
 */
final class CamoufoxProvisioning {

    /** How often the progress of a running installation is reported. */
    static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(5);

    private CamoufoxProvisioning() {
    }

    /** What a folder chosen for Camoufox holds. */
    enum Found {
        /** The pinned, verified distribution: below it, or the folder is the installation itself. */
        VERIFIED,
        /** A Camoufox or an unfinished installation that does not verify: never downloaded over. */
        FOREIGN,
        /** Nothing: a download target. */
        NONE
    }

    static Found inspect(Path chosen) {
        Path folder = chosen.toAbsolutePath().normalize();
        if (installedUnder(folder) || isVerifiedInstallation(folder)) {
            return Found.VERIFIED;
        }
        String executable = CamoufoxDistribution.executableName(CamoufoxDistribution.currentTarget());
        if (Files.exists(folder.resolve(executable)) || Files.exists(folder.resolve(installDirectoryName()))) {
            return Found.FOREIGN;
        }
        return Found.NONE;
    }

    /**
     * The installer for a chosen folder: the folder itself when it is a verified installation (any name),
     * otherwise the folder as root that holds (or will hold) {@code <release>-<target>}.
     */
    static CamoufoxInstaller installerFor(Path chosen) {
        Path folder = chosen.toAbsolutePath().normalize();
        if (!installedUnder(folder) && isVerifiedInstallation(folder)) {
            return CamoufoxInstaller.shared(folder.getParent(), folder, null);
        }
        return CamoufoxInstaller.shared(folder, null);
    }

    /** The verified executable in {@code folder}; fails readably when Camoufox is not installed there. */
    static Path installedExecutable(Path folder) {
        if (inspect(folder) != Found.VERIFIED) {
            throw new IllegalStateException("Camoufox is not installed in " + folder
                    + "; install it first (a browser start never downloads it)");
        }
        return installerFor(folder).executable();
    }

    /** The root of the installation in {@code folder} (Camoufox's runtime directory). */
    static Path installationRoot(Path folder) {
        return installerFor(folder).root();
    }

    /**
     * Downloads and verifies Camoufox into {@code folder} (a verified installation there is reused, a foreign
     * Camoufox is never downloaded over), reporting progress lines to {@code progress}. Blocks until done.
     */
    static Path install(Path folder, Consumer<String> progress) {
        if (inspect(folder) == Found.FOREIGN) {
            throw new IllegalStateException("a Camoufox that is not the verified build is in " + folder
                    + "; nothing is downloaded over it");
        }
        CamoufoxInstaller installer = installerFor(folder);
        while (true) {
            try {
                Path executable = installer.awaitExecutable(PROGRESS_INTERVAL);
                progress.accept("READY " + executable);
                return executable;
            } catch (CamoufoxInstaller.NotReadyException pending) {
                progress.accept(pending.getMessage());
            }
        }
    }

    /** What the installation in {@code folder} is doing right now (for a progress bar). */
    static CamoufoxInstaller.Status status(Path folder) {
        return installerFor(folder).status();
    }

    private static boolean isVerifiedInstallation(Path folder) {
        Path parent = folder.getParent();
        return parent != null && Files.isDirectory(folder) && new CamoufoxInstaller(parent,
                CamoufoxDistribution.builtIn(), CamoufoxDistribution.currentTarget(), folder).isInstalled();
    }

    private static boolean installedUnder(Path root) {
        return Files.isDirectory(root) && new CamoufoxInstaller(root, CamoufoxDistribution.builtIn(),
                CamoufoxDistribution.currentTarget()).isInstalled();
    }

    private static String installDirectoryName() {
        return CamoufoxDistribution.builtIn().name() + "-" + CamoufoxDistribution.currentTarget();
    }
}
