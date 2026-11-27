package com.aresstack.spaceharvester.browser.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Status classification with injected checks: every failure mode maps to its SPECIFIC status, plus the real classpath
 * checks against the module's own test classpath (the playwright4j driver replacement and the driver package must be
 * effective here: the regression guard for the exclude rules of the upstream driver).
 */
class BrowserProbeTest {

    @TempDir
    Path data;

    private static class FakeChecks implements BrowserProbe.Checks {
        List<String> command = List.of("java", "-cp", "x", "com.aresstack.playwright4j.driver.GraalDriverMain");
        boolean bundle = true;
        String executable = "C:\\fake\\chrome.exe";
        RuntimeException driverFailure;

        @Override
        public List<String> driverCommand() {
            if (driverFailure != null) {
                throw driverFailure;
            }
            return command;
        }

        @Override
        public boolean driverBundlePresent() {
            return bundle;
        }

        @Override
        public String browserExecutable(BrowserRuntimeConfig config) {
            return executable;
        }
    }

    private BrowserRuntimeConfig config(BrowserChannel channel) {
        return BrowserRuntimeConfig.of(channel, "", data, Duration.ZERO);
    }

    @Test
    void classifiesEveryFailureModeSpecifically() {
        assertEquals(Readiness.Status.READY, new BrowserProbe(new FakeChecks()).probe(config(BrowserChannel.CHROME))
                .status());

        FakeChecks wrongDriver = new FakeChecks();
        wrongDriver.command = List.of("node", "cli.js"); // the upstream Node driver won the classpath
        assertEquals(Readiness.Status.INCOMPATIBLE_DRIVER,
                new BrowserProbe(wrongDriver).probe(config(BrowserChannel.CHROME)).status());

        FakeChecks broken = new FakeChecks();
        broken.driverFailure = new IllegalStateException("no Driver class");
        assertEquals(Readiness.Status.INCOMPATIBLE_DRIVER,
                new BrowserProbe(broken).probe(config(BrowserChannel.CHROME)).status());

        FakeChecks noBundle = new FakeChecks();
        noBundle.bundle = false;
        assertEquals(Readiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                new BrowserProbe(noBundle).probe(config(BrowserChannel.CHROME)).status());

        FakeChecks noBrowser = new FakeChecks();
        noBrowser.executable = null;
        Readiness readiness = new BrowserProbe(noBrowser).probe(config(BrowserChannel.EDGE));
        assertEquals(Readiness.Status.BROWSER_NOT_INSTALLED, readiness.status());
        assertTrue(readiness.detail().contains("msedge") && readiness.detail().contains("never downloads"),
                "detail names the channel and forbids downloads");
    }

    @Test
    void camoufoxIsNotReadyUntilItIsInstalledAndTheProbeNeverDownloadsIt() {
        BrowserProbe.RealChecks real = new BrowserProbe.RealChecks();
        assertEquals(null, real.browserExecutable(config(BrowserChannel.CAMOUFOX)));
        FakeChecks checks = new FakeChecks() {
            @Override
            public String browserExecutable(BrowserRuntimeConfig config) {
                return real.browserExecutable(config);
            }
        };
        Readiness readiness = new BrowserProbe(checks).probe(config(BrowserChannel.CAMOUFOX));
        assertEquals(Readiness.Status.BROWSER_NOT_INSTALLED, readiness.status());
        assertTrue(readiness.detail().contains("install it first"), readiness.detail());
        assertEquals(0, data.toFile().list().length, "nothing was downloaded or created");
    }

    @Test
    void realClasspathUsesThePlaywright4jDriverReplacementAndBundlesTheJsDriver() {
        BrowserProbe.RealChecks real = new BrowserProbe.RealChecks();
        List<String> command = real.driverCommand();
        assertTrue(command.stream().anyMatch(part -> part.contains("GraalDriverMain")),
                "the effective Driver must spawn GraalDriverMain, not Node: " + command);
        assertTrue(real.driverBundlePresent(), "driver-bundle JS package must be on the module classpath");
    }
}
