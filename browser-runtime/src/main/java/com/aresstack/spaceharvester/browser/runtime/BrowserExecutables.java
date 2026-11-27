package com.aresstack.spaceharvester.browser.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the locally installed browsers (Firefox, Chrome, Edge) are: the program file the host configured, else the
 * standard install locations (autodetect). The runtime never downloads these browsers.
 */
public final class BrowserExecutables {

    private BrowserExecutables() {
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

    /**
     * The executable to start: the configured one when it exists, else the detected one; {@code null} when none.
     * A configured file that does not exist is never replaced by another browser.
     */
    public static String effective(String channel, String configured) {
        String chosen = configured == null ? "" : configured.trim();
        if (!chosen.isEmpty()) {
            return new File(chosen).isFile() ? new File(chosen).getAbsolutePath() : null;
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
        boolean firefox = BrowserChannel.FIREFOX.id().equals(channel);
        boolean edge = BrowserChannel.EDGE.id().equals(channel);
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
