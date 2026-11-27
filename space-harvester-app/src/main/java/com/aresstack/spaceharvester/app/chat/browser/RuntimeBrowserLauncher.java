package com.aresstack.spaceharvester.app.chat.browser;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.playwright.AddonPackager;
import com.aresstack.askai.browser.playwright.BrowserLauncher;
import com.aresstack.askai.browser.playwright.LaunchedBrowser;
import com.aresstack.askai.browser.playwright.PlaywrightReadiness;
import com.aresstack.spaceharvester.browser.runtime.BrowserAddon;
import com.aresstack.spaceharvester.browser.runtime.BrowserChannel;
import com.aresstack.spaceharvester.browser.runtime.BrowserRuntime;
import com.aresstack.spaceharvester.browser.runtime.BrowserStartException;
import com.aresstack.spaceharvester.browser.runtime.Readiness;
import com.aresstack.spaceharvester.browser.runtime.RunningBrowser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Adapter vom Port {@link BrowserLauncher} der Research4j-Bibliothek auf die Browser-Laufzeit des Space Harvester
 * (ADR 0046): Research4j sagt, was es braucht (dauerhaftes Profil zu einem Schlüssel oder flüchtiger Browser, sein
 * Add-on), die Laufzeit startet den Browser, den die App eingestellt hat. Weder Research4j noch die Laufzeit kennen
 * einander.
 */
final class RuntimeBrowserLauncher implements BrowserLauncher {

    private final BrowserRuntime runtime;

    RuntimeBrowserLauncher(BrowserRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public PlaywrightReadiness readiness() {
        Readiness readiness = runtime.readiness();
        return new PlaywrightReadiness(PlaywrightReadiness.Status.valueOf(readiness.status().name()),
                readiness.detail());
    }

    /**
     * Der Schlüssel eines Chats ergibt ein eigenes dauerhaftes Profil der Laufzeit. Die Chat-Engine
     * ({@code PlaywrightChatEngine}) übergibt statt eines Schlüssels den Profilordner, den die App für den Chat führt
     * ({@code BrowserProfileStore}); den nutzt die Laufzeit selbst, damit das Löschen des Chats auch sein Profil löscht.
     */
    @Override
    public LaunchedBrowser persistent(String profileKey, boolean headless, AddonPackager addon)
            throws BrowserException {
        try {
            Path folder = profileKey == null ? null : Paths.get(profileKey);
            RunningBrowser running = folder != null && folder.isAbsolute()
                    ? runtime.persistentIn(folder, headless, addon(addon))
                    : runtime.persistent(profileKey, headless, addon(addon));
            return new Launched(running);
        } catch (BrowserStartException failed) {
            throw new BrowserException(failed.getMessage(), failed);
        }
    }

    @Override
    public LaunchedBrowser ephemeral(boolean headless, AddonPackager addon) throws BrowserException {
        try {
            return new Launched(runtime.ephemeral(headless, addon(addon)));
        } catch (BrowserStartException failed) {
            throw new BrowserException(failed.getMessage(), failed);
        }
    }

    private static BrowserAddon addon(AddonPackager packager) {
        if (packager == null) {
            return null;
        }
        return new BrowserAddon() {
            @Override
            public Path prepare(Path parent, boolean chromium) throws IOException {
                return packager.prepare(parent, chromium);
            }

            @Override
            public String chromiumExtensionId() {
                return packager.chromiumExtensionId();
            }
        };
    }

    /** Der laufende Browser der Laufzeit, so wie Research4j ihn sieht. */
    private record Launched(RunningBrowser running) implements LaunchedBrowser {

        @Override
        public Playwright playwright() {
            return running.playwright();
        }

        @Override
        public Browser browser() {
            return running.browser();
        }

        @Override
        public BrowserContext context() {
            return running.context();
        }

        @Override
        public boolean camoufox() {
            return running.channel() == BrowserChannel.CAMOUFOX;
        }

        @Override
        public boolean chromiumExtension() {
            return !running.channel().firefoxFamily() && running.addonLoaded();
        }

        @Override
        public boolean addonLoaded() {
            return running.addonLoaded();
        }

        @Override
        public String addonFailure() {
            return running.addonFailure();
        }

        @Override
        public void close() {
            running.close();
        }

        @Override
        public void abandon() {
            running.abandon();
        }
    }
}
