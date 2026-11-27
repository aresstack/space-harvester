package com.aresstack.askai.browser.playwright;

/**
 * Camoufox setup for the host UI (setup wizard, Research Agent → Browser): where AskAI's verified Camoufox is, what
 * a chosen folder holds, and the download into a chosen folder. Browsers themselves are started by
 * {@link PlaywrightBrowserRuntime}; this class never starts one.
 */
public final class CamoufoxSetup {

    private CamoufoxSetup() {
    }

    /** Downloads and verifies the managed Camoufox without starting a browser; returns 0 on success. */
    public static int installCamoufox() {
        return CamoufoxProvisioning.installOnly();
    }

    /** What the managed Camoufox installation is doing right now (for a progress bar, e.g. in the setup wizard). */
    public static com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller.Status camoufoxStatus() {
        return CamoufoxProvisioning.installer().status();
    }

    /** Status of the Camoufox in a chosen folder (the folder itself or the root that holds the installation). */
    public static com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller.Status camoufoxStatus(String folder) {
        return CamoufoxProvisioning.installerFor(java.nio.file.Paths.get(folder)).status();
    }

    /**
     * What a chosen folder holds: {@code VERIFIED} (AskAI's pinned Camoufox, used as it is), {@code FOREIGN} (a
     * Camoufox that does not verify: never downloaded over) or {@code NONE} (a download target).
     */
    public static String inspectCamoufox(String folder) {
        return CamoufoxProvisioning.inspect(java.nio.file.Paths.get(folder).toAbsolutePath().normalize()).name();
    }

    /** The verified Camoufox found without configuration (AskAI's own installation folder), or "". */
    public static String foundCamoufox() {
        java.nio.file.Path found = CamoufoxProvisioning.foundInstallation();
        return found == null ? "" : found.toString();
    }

    /** Where Camoufox goes when no folder is chosen. */
    public static String defaultCamoufoxFolder() {
        return CamoufoxProvisioning.defaultRoot().toString();
    }

    /**
     * Downloads and verifies Camoufox into a chosen folder (an existing verified installation there is reused, a
     * foreign Camoufox is never downloaded over) and uses it from then on; returns 0 on success.
     */
    public static int installCamoufox(String folder) {
        java.nio.file.Path chosen = java.nio.file.Paths.get(folder).toAbsolutePath().normalize();
        try {
            if (CamoufoxProvisioning.inspect(chosen) == CamoufoxProvisioning.Found.FOREIGN) {
                throw new IllegalStateException("a Camoufox that is not AskAI's verified build is in " + chosen
                        + "; nothing is downloaded over it");
            }
            CamoufoxProvisioning.awaitInstalled(CamoufoxProvisioning.installerFor(chosen));
            useCamoufox(chosen.toString());
            return 0;
        } catch (RuntimeException failed) {
            System.err.println("[browser] " + CamoufoxProvisioning.PROGRESS_MARKER + " FAILED " + failed.getMessage());
            return 1;
        }
    }

    /**
     * Uses the Camoufox in {@code folder} for every browser started afterwards in this process, when it holds the
     * verified installation; blank = the automatic choice again.
     */
    public static void useCamoufox(String folder) {
        if (folder == null || folder.trim().isEmpty()) {
            System.clearProperty(CamoufoxProvisioning.ROOT_PROPERTY);
            return;
        }
        java.nio.file.Path chosen = java.nio.file.Paths.get(folder.trim()).toAbsolutePath().normalize();
        if (CamoufoxProvisioning.inspect(chosen) == CamoufoxProvisioning.Found.VERIFIED) {
            System.setProperty(CamoufoxProvisioning.ROOT_PROPERTY, chosen.toString());
        }
    }
}
