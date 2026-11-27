package com.aresstack.askai.browser.playwright;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AskAI Research add-on. Firefox (sources in {@code src/main/resources/askai-addon/firefox}): written as an
 * unpacked add-on directory next to a browser's profile at every start, so Camoufox loads it through
 * {@code CAMOU_CONFIG.addons} like its bundled add-ons. The directory carries the {@code bridge.json} of THIS start
 * (port and token of its {@link AddonBridge}); a stale one from an earlier start is always overwritten.
 *
 * <p>Chrome/Edge (stage 4) get the Manifest V3 variant from {@code askai-addon/chrome} ({@link #installChrome}): its
 * own manifest and service worker, the same panel files as Firefox. Chrome loads it through the DevTools protocol
 * ({@code Extensions.loadUnpacked}) into the chat browser's profile.</p>
 */
final class ResearchAddon {

    /** The add-on id from its manifest (browser_specific_settings.gecko.id). */
    static final String ID = "research-hud@askai.aresstack.com";
    static final String DIRECTORY = "askai-research-addon";
    private static final String RESOURCES = "/askai-addon/firefox/";
    /** Every file of the add-on; the jar cannot be listed, so the set is spelled out here. */
    static final List<String> FILES = List.of(
            "manifest.json", "background.js", "panel.html", "panel.css", "panel.js", "icon.svg");
    /**
     * The Chrome extension's id. Fixed by the public {@code key} in its manifest (the id is derived from that key), so
     * the bridge can accept exactly this extension origin, wherever the extension directory lies.
     */
    static final String CHROME_ID = "elobpccbfihfcmjgkadloggolgnnfhnb";
    /** Every file of the Chrome extension and its resource: own manifest and service worker, the shared panel. */
    static final Map<String, String> CHROME_FILES = chromeFiles();

    private ResearchAddon() {
    }

    /**
     * Write the add-on to {@code <parent>/askai-research-addon} with the bridge configuration of this start.
     * @param bridgeConfig the {@link AddonBridge#clientConfig()} JSON, or null for an add-on that stays idle
     * @return the add-on directory, for {@code CamoufoxLaunch.withAddon}
     */
    static Path install(Path parent, String bridgeConfig) throws IOException {
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (String file : FILES) {
            files.put(file, RESOURCES + file);
        }
        return write(parent, files, bridgeConfig);
    }

    /**
     * Write the Chrome/Edge extension to {@code <parent>/askai-research-addon} with the bridge configuration of this
     * start. @return the extension directory, for {@code Extensions.loadUnpacked}
     */
    static Path installChrome(Path parent, String bridgeConfig) throws IOException {
        return write(parent, CHROME_FILES, bridgeConfig);
    }

    private static Map<String, String> chromeFiles() {
        Map<String, String> files = new LinkedHashMap<String, String>();
        files.put("manifest.json", "/askai-addon/chrome/manifest.json");
        files.put("background.js", "/askai-addon/chrome/background.js");
        for (String shared : List.of("panel.html", "panel.css", "panel.js")) {
            files.put(shared, RESOURCES + shared);
        }
        return java.util.Collections.unmodifiableMap(files);
    }

    private static Path write(Path parent, Map<String, String> files, String bridgeConfig) throws IOException {
        Path target = parent.resolve(DIRECTORY);
        Files.createDirectories(target);
        for (Map.Entry<String, String> file : files.entrySet()) {
            try (InputStream in = ResearchAddon.class.getResourceAsStream(file.getValue())) {
                if (in == null) {
                    throw new IllegalStateException(file.getValue() + " missing from the browser-playwright jar");
                }
                Files.copy(in, target.resolve(file.getKey()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path config = target.resolve("bridge.json");
        if (bridgeConfig == null) {
            Files.deleteIfExists(config);
        } else {
            Files.writeString(config, bridgeConfig, StandardCharsets.UTF_8);
        }
        return target;
    }
}
