package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.sun.jna.NativeLibrary;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Locate an installed libVLC before constructing a native media player. */
final class VlcRuntimeLocator {

    private static final String CUSTOM_PATH = "spaceharvester.vlc.path";

    private VlcRuntimeLocator() {
    }

    static boolean configure() {
        for (File directory : candidateDirectories()) {
            if (containsLibVlc(directory)) {
                configureLibraryPath(directory);
                return true;
            }
        }
        try {
            return new NativeDiscovery().discover();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static List<File> candidateDirectories() {
        List<File> directories = new ArrayList<>();
        append(directories, System.getProperty(CUSTOM_PATH));
        append(directories, System.getenv("VLC_HOME"));
        append(directories, System.getenv("VLC_INSTALL_DIR"));
        append(directories, System.getenv("ProgramFiles"), "VideoLAN", "VLC");
        append(directories, System.getenv("ProgramFiles(x86)"), "VideoLAN", "VLC");
        append(directories, System.getenv("LOCALAPPDATA"), "Programs", "VideoLAN", "VLC");
        append(directories, "/Applications/VLC.app/Contents/MacOS/lib");
        append(directories, "/usr/lib/x86_64-linux-gnu");
        append(directories, "/usr/lib64");
        append(directories, "/usr/lib");
        return directories;
    }

    private static void append(List<File> directories, String path, String... children) {
        if (path == null || path.isBlank()) {
            return;
        }
        File directory = new File(path);
        for (String child : children) {
            directory = new File(directory, child);
        }
        directories.add(directory);
    }

    static boolean containsLibVlc(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return false;
        }
        String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String file = system.contains("win") ? "libvlc.dll"
                : system.contains("mac") ? "libvlc.dylib" : "libvlc.so";
        return new File(directory, file).isFile();
    }

    private static void configureLibraryPath(File directory) {
        NativeLibrary.addSearchPath("libvlc", directory.getAbsolutePath());
        NativeLibrary.addSearchPath("libvlccore", directory.getAbsolutePath());
        String existing = System.getProperty("jna.library.path", "");
        String path = directory.getAbsolutePath();
        if (!java.util.Arrays.asList(existing.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .contains(path)) {
            System.setProperty("jna.library.path", existing.isEmpty() ? path : path + File.pathSeparator + existing);
        }
        File plugins = new File(directory, "plugins");
        if (plugins.isDirectory()) {
            System.setProperty("VLC_PLUGIN_PATH", plugins.getAbsolutePath());
        }
    }
}
