package com.aresstack.askai.browser.playwright;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Status classification with injected checks: every failure mode maps to its SPECIFIC status (no blanket
 * NOT_INSTALLED), plus the real classpath checks against the module's own test classpath (the playwright4j
 * Driver replacement and the driver bundle must be effective here — that is the classpath-composition
 * regression guard for the upstream-driver exclude rules).
 */
public class PlaywrightCapabilityProbeTest {

    private static final class FakeChecks implements PlaywrightCapabilityProbe.Checks {
        List<String> command = Arrays.asList("java", "-cp", "x", "com.aresstack.playwright4j.driver.GraalDriverMain");
        boolean bundle = true;
        String executable = "C:\\fake\\chrome.exe";
        RuntimeException driverFailure;

        public List<String> driverCommand() {
            if (driverFailure != null) {
                throw driverFailure;
            }
            return command;
        }

        public boolean driverBundlePresent() {
            return bundle;
        }

        public String browserExecutable(String channel) {
            return executable;
        }
    }

    @Test
    public void classifiesEveryFailureModeSpecifically() {
        FakeChecks checks = new FakeChecks();
        assertEquals(PlaywrightReadiness.Status.READY,
                new PlaywrightCapabilityProbe(checks).probe("chrome").getStatus());

        FakeChecks wrongDriver = new FakeChecks();
        wrongDriver.command = Arrays.asList("node", "cli.js"); // the upstream Node driver won the classpath
        assertEquals(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                new PlaywrightCapabilityProbe(wrongDriver).probe("chrome").getStatus());

        FakeChecks broken = new FakeChecks();
        broken.driverFailure = new IllegalStateException("no Driver class");
        assertEquals(PlaywrightReadiness.Status.INCOMPATIBLE_DRIVER,
                new PlaywrightCapabilityProbe(broken).probe("chrome").getStatus());

        FakeChecks noBundle = new FakeChecks();
        noBundle.bundle = false;
        assertEquals(PlaywrightReadiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                new PlaywrightCapabilityProbe(noBundle).probe("chrome").getStatus());

        FakeChecks noBrowser = new FakeChecks();
        noBrowser.executable = null;
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe(noBrowser).probe("msedge");
        assertEquals(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED, readiness.getStatus());
        assertTrue("detail names the channel and forbids downloads",
                readiness.getDetail().contains("msedge") && readiness.getDetail().contains("never downloads"));
    }

    @Test
    public void realClasspathUsesThePlaywright4jDriverReplacementAndBundlesTheJsDriver() {
        PlaywrightCapabilityProbe.RealChecks real = new PlaywrightCapabilityProbe.RealChecks();
        List<String> command = real.driverCommand();
        boolean graal = false;
        for (String part : command) {
            graal |= part.contains("GraalDriverMain");
        }
        assertTrue("the effective Driver must spawn GraalDriverMain, not Node: " + command, graal);
        assertTrue("driver-bundle JS package must be on the module classpath", real.driverBundlePresent());
    }
}
