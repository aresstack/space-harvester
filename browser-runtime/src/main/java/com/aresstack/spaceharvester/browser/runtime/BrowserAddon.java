package com.aresstack.spaceharvester.browser.runtime;

import java.io.IOException;
import java.nio.file.Path;

/**
 * An add-on the host wants in the browser (for the web search: the research add-on of Research4j). The runtime
 * decides where it lies and how it is loaded; it reports when it cannot load it and never drops it silently.
 */
public interface BrowserAddon {

    /**
     * Writes the add-on below {@code parent}: a Firefox add-on file, or for Chrome/Edge ({@code chromium}) an
     * unpacked extension directory. @return the add-on file or extension directory
     */
    Path prepare(Path parent, boolean chromium) throws IOException;

    /** The id Chrome/Edge must report for the unpacked extension (fixed by its manifest key). */
    String chromiumExtensionId();
}
