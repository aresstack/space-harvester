package com.aresstack.spaceharvester.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeFinderTest {

    @Test
    void versionsAreReadTheOldWayAndTheNew() {
        assertEquals(8, JavaRuntimeFinder.majorOf("1.8.0_392"));
        assertEquals(8, JavaRuntimeFinder.majorOf("1.8"));
        assertEquals(21, JavaRuntimeFinder.majorOf("21.0.5"));
        assertEquals(22, JavaRuntimeFinder.majorOf("22-ea"));
        assertEquals(-1, JavaRuntimeFinder.majorOf("unknown"));
        assertEquals(-1, JavaRuntimeFinder.majorOf(null));
    }

    @Test
    void theAnswerOfAJavaIsUnderstood() {
        assertEquals(21, JavaRuntimeFinder.parse("Property settings:\n    java.vendor = Eclipse Adoptium\n"
                + "    java.version = 21.0.5\n    java.version.date = 2024-10-15\n"));
        assertEquals(8, JavaRuntimeFinder.parse("openjdk version \"1.8.0_392\"\nOpenJDK Runtime Environment\n"));
        assertEquals(-1, JavaRuntimeFinder.parse("not a java at all"));
    }

    @Test
    void anInstallationThatNamesAnotherVersionIsNotStarted(@TempDir Path folder) throws Exception {
        Path bin = Files.createDirectories(folder.resolve("jdk-17").resolve("bin"));
        File java = Files.writeString(bin.resolve("java"), "not executable").toFile();
        Files.writeString(folder.resolve("jdk-17").resolve("release"), "IMPLEMENTOR=\"Somebody\"\nJAVA_VERSION=\"17.0.9\"\n");
        assertEquals(java.getAbsoluteFile(), JavaRuntimeFinder.executable(folder.resolve("jdk-17").toFile()));
        assertEquals(17, new JavaRuntimeFinder(21, null).majorVersion(java));
        assertNull(JavaRuntimeFinder.executable(folder.resolve("nothing").toFile()));
    }

    @Test
    void theJavaTheTestsRunOnIsFoundAndRemembered(@TempDir Path folder) throws Exception {
        int own = Runtime.version().feature();
        File memory = folder.resolve("launcher.properties").toFile();
        System.setProperty("space-harvester.java", System.getProperty("java.home"));
        try {
            File found = new JavaRuntimeFinder(own, memory).find();
            assertTrue(found != null && found.isFile(), "found through space-harvester.java");
            assertTrue(Files.readString(memory.toPath()).contains("java" + own + "="), "remembered for next time");
            System.clearProperty("space-harvester.java");
            assertEquals(found, new JavaRuntimeFinder(own, memory).find(), "taken from the memory");
        } finally {
            System.clearProperty("space-harvester.java");
        }
    }
}
