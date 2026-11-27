package com.aresstack.askai.browser.playwright;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the locally installed browsers (Firefox, Chrome, Edge) are: a location the user chose, else the standard
 * install locations (autodetect). AskAI never downloads these browsers. The chosen location is kept per channel in
 * this process ({@value #PROPERTY_PREFIX}{@code <channel>}) and read by every browser started afterwards.
 */
public final class BrowserExecutables {

    /** The local Firefox, started over WebDriver BiDi like Camoufox (it is recognised as a bot more often). */
    public static final String FIREFOX = "firefox";
    static final String PROPERTY_PREFIX = "askai.browser.executable.";

    private BrowserExecutables() {
    }

    /** Uses {@code path} as the browser of {@code channel} from now on; blank = autodetect again. */
    public static void use(String channel, String path) {
        String key = PROPERTY_PREFIX + normalize(channel);
        if (path == null || path.trim().isEmpty()) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, path.trim());
        }
    }

    /** The location the user chose for {@code channel}, or "". */
    public static String configured(String channel) {
        return System.getProperty(PROPERTY_PREFIX + normalize(channel), "").trim();
    }

    /** The browser found at the standard install locations of {@code channel}, or "" when none is installed. */
    public static String detected(String channel) {
        for (String candidate : candidatePaths(normalize(channel))) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return "";
    }

    /** The executable to start: the chosen one when it exists, else the detected one; {@code null} when none. */
    public static String effective(String channel) {
        String configured = configured(channel);
        if (!configured.isEmpty()) {
            return new File(configured).isFile() ? new File(configured).getAbsolutePath() : null;
        }
        String found = detected(channel);
        return found.isEmpty() ? null : found;
    }

    private static String normalize(String channel) {
        return channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
    }

    /** Standard install locations only: discovery, never installation. */
    static List<String> candidatePaths(String channel) {
        List<String> paths = new ArrayList<String>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean firefox = FIREFOX.equals(channel);
        boolean edge = "msedge".equals(channel);
        if (os.contains("win")) {
            for (String root : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
                    System.getenv("LOCALAPPDATA")}) {
                if (root == null) {
                    continue;
                }
                if (firefox) {
                    paths.add(root + "\\Mozilla Firefox\\firefox.exe");
                } else if (edge) {
                    paths.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
                } else {
                    paths.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
                }
            }
        } else if (os.contains("mac")) {
            paths.add(firefox ? "/Applications/Firefox.app/Contents/MacOS/firefox"
                    : edge ? "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
                    : "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        } else if (firefox) {
            paths.add("/usr/bin/firefox");
            paths.add("/usr/lib/firefox/firefox");
            paths.add("/snap/bin/firefox");
        } else if (edge) {
            paths.add("/usr/bin/microsoft-edge");
            paths.add("/opt/microsoft/msedge/msedge");
        } else {
            paths.add("/usr/bin/google-chrome");
            paths.add("/usr/bin/google-chrome-stable");
            paths.add("/opt/google/chrome/chrome");
        }
        return paths;
    }
}
