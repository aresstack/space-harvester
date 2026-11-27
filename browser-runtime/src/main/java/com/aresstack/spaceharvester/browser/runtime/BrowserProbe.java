package com.aresstack.spaceharvester.browser.runtime;

import com.microsoft.playwright.impl.driver.Driver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks before a browser starts (moved from Ask AI's capability probe), in order: (1) the effective
 * {@code com.microsoft.playwright.impl.driver.Driver} is playwright4j's replacement (its child command runs
 * {@code GraalDriverMain}; the upstream one would try to install and spawn Node), (2) the Playwright Core JS driver
 * package is on the classpath (embedded in the playwright4j jar), (3) the configured browser is there: an installed
 * Firefox/Chrome/Edge, or an installed Camoufox. Launchability itself is verified by the start. The probe NEVER
 * downloads or installs anything.
 */
final class BrowserProbe {

    /** The environment checks, injectable for classification tests. */
    interface Checks {
        /** @return the driver child-process command line, or null when no Driver class is usable. */
        List<String> driverCommand();

        boolean driverBundlePresent();

        /** @return the browser executable to start, or null when none exists. */
        String browserExecutable(BrowserRuntimeConfig config);
    }

    private final Checks checks;

    BrowserProbe() {
        this(new RealChecks());
    }

    BrowserProbe(Checks checks) {
        this.checks = checks;
    }

    Readiness probe(BrowserRuntimeConfig config) {
        List<String> command;
        try {
            command = checks.driverCommand();
        } catch (Throwable ex) {
            return new Readiness(Readiness.Status.INCOMPATIBLE_DRIVER, "Driver probe failed: " + ex.getMessage());
        }
        boolean graal = command != null && command.stream()
                .anyMatch(part -> part != null && part.contains("GraalDriverMain"));
        if (!graal) {
            return new Readiness(Readiness.Status.INCOMPATIBLE_DRIVER,
                    "The effective Playwright Driver is not the playwright4j GraalJS replacement; "
                            + "check that com.microsoft.playwright:driver is excluded from the classpath.");
        }
        if (!checks.driverBundlePresent()) {
            return new Readiness(Readiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                    "Playwright Core driver package (embedded in com.aresstack:playwright4j) is not on the classpath.");
        }
        String channel = config.channel().id();
        String executable = checks.browserExecutable(config);
        if (executable == null) {
            return new Readiness(Readiness.Status.BROWSER_NOT_INSTALLED, config.channel() == BrowserChannel.CAMOUFOX
                    ? "Camoufox is not installed in " + config.camoufoxRoot() + "; install it first."
                    : "No installed browser found for channel '" + channel
                    + "'. Install it or choose its program file; the runtime never downloads Firefox, Chrome or Edge.");
        }
        return new Readiness(Readiness.Status.READY, "channel=" + channel + " executable=" + executable);
    }

    /** Production checks against the real classpath and file system. */
    static final class RealChecks implements Checks {

        @Override
        public List<String> driverCommand() {
            Map<String, String> env = new LinkedHashMap<>();
            DriverClasspathJar.install();
            return Driver.createAndInstall(env, false).createProcessBuilder().command();
        }

        @Override
        public boolean driverBundlePresent() {
            // playwright4j >= 0.3.0 embeds the Core JS package platform-neutrally under driver/package/.
            return BrowserProbe.class.getClassLoader().getResource("driver/package/cli.js") != null;
        }

        @Override
        public String browserExecutable(BrowserRuntimeConfig config) {
            if (config.channel() == BrowserChannel.CAMOUFOX) {
                Path root = config.camoufoxRoot();
                return CamoufoxProvisioning.inspect(root) == CamoufoxProvisioning.Found.VERIFIED
                        ? CamoufoxProvisioning.installedExecutable(root).toString() : null;
            }
            return BrowserExecutables.effective(config.channel().id(), config.executable());
        }
    }
}
