package com.aresstack.askai.browser.playwright;

import com.microsoft.playwright.impl.driver.Driver;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real capability probing before the browser runtime reports the Playwright backend as usable. Checks, in order:
 * (1) the effective {@code com.microsoft.playwright.impl.driver.Driver} is playwright4j's replacement (its
 * child command runs {@code GraalDriverMain} — the upstream one would try to install/spawn Node),
 * (2) the Playwright Core JS driver package is on the classpath (embedded in the playwright4j jar),
 * (3) an executable for the configured browser channel exists locally — except for the managed Camoufox,
 * which the launch installs and verifies itself ({@link CamoufoxProvisioning}).
 * Launchability itself is verified by the actual launch at session start ({@code BROWSER_START_FAILED}).
 * The probe itself NEVER downloads or installs anything.
 */
final class PlaywrightCapabilityProbe {

    /** The three environment checks, injectable for classification tests. */
    interface Checks {
        /** @return the driver child-process command line, or null when no Driver class is usable. */
        List<String> driverCommand();

        boolean driverBundlePresent();

        /** @return the resolved browser executable path, or null when none exists. */
        String browserExecutable(String channel);
    }

    private final Checks checks;

    PlaywrightCapabilityProbe() {
        this(new RealChecks());
    }

    PlaywrightCapabilityProbe(Checks checks) {
        this.checks = checks;
    }

    PlaywrightReadiness probe(String channel) {
        List<String> command;
        try {
            command = checks.driverCommand();
        } catch (Throwable ex) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                    "Driver probe failed: " + ex.getMessage());
        }
        boolean graal = false;
        if (command != null) {
            for (String part : command) {
                if (part != null && part.contains("GraalDriverMain")) {
                    graal = true;
                    break;
                }
            }
        }
        if (!graal) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                    "The effective Playwright Driver is not the playwright4j GraalJS replacement; "
                            + "check that com.microsoft.playwright:driver is excluded from the classpath.");
        }
        if (!checks.driverBundlePresent()) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                    "Playwright Core driver package (embedded in com.aresstack:playwright4j) is not on the classpath.");
        }
        if (CamoufoxProvisioning.CHANNEL.equals(channel)) {
            // Managed: not looked up on the machine — the launch installs (once) and verifies it.
            return new PlaywrightReadiness(PlaywrightReadiness.Status.READY,
                    "channel=camoufox executable=managed under " + CamoufoxProvisioning.browserRoot());
        }
        String executable = checks.browserExecutable(channel);
        if (executable == null) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED,
                    "No installed browser found for channel '" + channel
                            + "'. Install it or choose its location under Research Agent \u2192 Browser; AskAI never downloads"
                            + " Firefox, Chrome or Edge.");
        }
        return new PlaywrightReadiness(PlaywrightReadiness.Status.READY,
                "channel=" + channel + " executable=" + executable);
    }

    /** Production checks against the real classpath and file system. */
    static final class RealChecks implements Checks {

        public List<String> driverCommand() {
            Map<String, String> env = new LinkedHashMap<String, String>();
            DriverClasspathJar.install();
            return Driver.createAndInstall(env, false).createProcessBuilder().command();
        }

        public boolean driverBundlePresent() {
            // playwright4j >= 0.3.0 embeds the Core JS package platform-neutrally under driver/package/.
            return PlaywrightCapabilityProbe.class.getClassLoader().getResource("driver/package/cli.js") != null;
        }

        public String browserExecutable(String channel) {
            return BrowserExecutables.effective(channel);
        }
    }
}
