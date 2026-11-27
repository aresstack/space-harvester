package com.aresstack.askai.browser.playwright;

import java.io.IOException;
import java.nio.file.Path;

/** Writes the research add-on for one launch; the launcher decides where and how the browser loads it. */
public interface AddonPackager {

    /**
     * @param parent   the directory the launcher chose for the add-on
     * @param chromium whether the browser takes a Chromium extension (else a Firefox add-on)
     * @return the add-on directory to load
     */
    Path prepare(Path parent, boolean chromium) throws IOException;

    /** The id the Chromium extension gets from its manifest key (the launcher pins and checks it). */
    String chromiumExtensionId();

    /** The research add-on of {@link ResearchAddon} with the bridge configuration {@code bridge} gives it. */
    static AddonPackager research(AddonBridge bridge, boolean readScriptsAsFiles) {
        return new AddonPackager() {
            public Path prepare(Path parent, boolean chromium) throws IOException {
                if (!chromium) {
                    return ResearchAddon.install(parent, bridge.clientConfig());
                }
                Path extension = ResearchAddon.installChrome(parent, bridge.clientConfig());
                if (readScriptsAsFiles) {
                    bridge.readScriptsAsFilesIn(extension);
                }
                return extension;
            }

            public String chromiumExtensionId() {
                return ResearchAddon.CHROME_ID;
            }
        };
    }
}
