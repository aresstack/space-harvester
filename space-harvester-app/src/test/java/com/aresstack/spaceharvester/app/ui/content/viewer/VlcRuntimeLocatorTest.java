package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class VlcRuntimeLocatorTest {

    @TempDir
    Path directory;

    @Test
    void resolvesNativeVlcOnMachinesWithAnInstalledCopy() {
        java.io.File installed = new java.io.File("C:\\Program Files\\VideoLAN\\VLC");
        org.junit.jupiter.api.Assumptions.assumeTrue(VlcRuntimeLocator.containsLibVlc(installed));
        assertThat(VlcRuntimeLocator.configure()).isTrue();
        try (com.sun.jna.NativeLibrary nativeLibrary = com.sun.jna.NativeLibrary.getInstance("libvlc")) {
            assertThat(nativeLibrary.getFunction("libvlc_get_version")).isNotNull();
        }
    }

    @Test
    void rejectsDirectoriesThatDoNotContainNativeVlcLibraries() {
        assertThat(VlcRuntimeLocator.containsLibVlc(directory.toFile())).isFalse();
    }

    @Test
    void recognizesNativeLibraryInConfiguredDirectory() throws Exception {
        String system = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String library = system.contains("win") ? "libvlc.dll"
                : system.contains("mac") ? "libvlc.dylib" : "libvlc.so";
        Files.createFile(directory.resolve(library));
        assertThat(VlcRuntimeLocator.containsLibVlc(directory.toFile())).isTrue();
    }
}
