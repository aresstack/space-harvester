package com.aresstack.spaceharvester.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a Java installation of one feature version on this computer, for a
 * launcher that was started with another one. Written for Java 8.
 *
 * <p>The search follows the one the Enterprise AI Client had for its sidecar
 * (JavaRuntimes, FileSystemJavaRuntimeDiscovery, ProcessJavaRuntimeProbe; there
 * until 0.1.43): the remembered installation first, then what was named
 * explicitly, {@code JAVA_HOME}, every {@code java} on the {@code PATH}, and the
 * folders installers usually use. A candidate is asked for its version with
 * {@code java -XshowSettings:properties -version}; ten seconds protect against
 * one that hangs. Unlike there, only the exact version counts and the search
 * ends with the first hit.</p>
 */
final class JavaRuntimeFinder {

    private static final long PROBE_TIMEOUT_MILLIS = 10000L;
    private static final Pattern VERSION_PROPERTY = Pattern.compile("(?m)^\\s*java\\.version\\s*=\\s*(\\S+)\\s*$");
    private static final Pattern QUOTED_VERSION = Pattern.compile("\"([0-9][0-9._\\-a-zA-Z+]*)\"");
    private static final Pattern RELEASE_VERSION = Pattern.compile("(?m)^\\s*JAVA_VERSION\\s*=\\s*\"?([^\"\\s]+)\"?\\s*$");
    private static final String[] VENDORS = {"Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu",
            "Azul", "Amazon Corretto", "BellSoft", "RedHat", "Red Hat", "SapMachine", "JetBrains", "Semeru", "Oracle",
            "OpenJDK"};

    private final int wanted;
    /** Where the installation found last time is remembered; {@code null} to remember nothing. */
    private final File memory;
    private final String memoryKey;

    JavaRuntimeFinder(int wanted, File memory) {
        this.wanted = wanted;
        this.memory = memory;
        this.memoryKey = "java" + wanted;
    }

    /** The {@code java} executable of an installation of the wanted version, or {@code null} if there is none. */
    File find() {
        File remembered = executable(remembered());
        if (remembered != null && majorVersion(remembered) == wanted) {
            return remembered; // checked again, but nothing else is searched
        }
        for (File candidate : candidates()) {
            if (majorVersion(candidate) == wanted) {
                remember(candidate);
                return candidate;
            }
        }
        return null;
    }

    /** Every {@code java} executable worth asking, the likeliest first, each one once. */
    List<File> candidates() {
        Map<String, File> found = new LinkedHashMap<String, File>();
        add(found, file(System.getProperty("space-harvester.java")));
        add(found, file(System.getenv("SPACE_HARVESTER_JAVA")));
        add(found, file(System.getenv("JAVA_HOME_" + wanted + "_X64"))); // set by GitHub's runners
        add(found, file(System.getenv("JAVA_HOME")));
        String path = System.getenv("PATH");
        if (path != null) {
            // What where.exe would list: the PATH names bin folders (or Oracle's javapath), not installations.
            for (String entry : path.split(File.pathSeparator)) {
                File folder = file(entry);
                if (folder != null) {
                    add(found, new File(folder, "java.exe"));
                    add(found, new File(folder, "java"));
                }
            }
        }
        for (File root : searchRoots()) {
            for (File child : directories(root)) {
                add(found, child);
                for (File nested : directories(child)) {
                    add(found, nested); // scoop\apps\temurin21-jdk\current, JavaVirtualMachines\x\Contents
                    add(found, new File(nested, "Home"));
                }
            }
        }
        return new ArrayList<File>(found.values());
    }

    private static List<File> searchRoots() {
        List<File> roots = new ArrayList<File>();
        for (String programFiles : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432")}) {
            File base = file(programFiles);
            if (base != null) {
                for (String vendor : VENDORS) {
                    roots.add(new File(base, vendor));
                }
            }
        }
        File programFilesX86 = file(System.getenv("ProgramFiles(x86)"));
        if (programFilesX86 != null) {
            roots.add(new File(programFilesX86, "Java"));
        }
        File localAppData = file(System.getenv("LOCALAPPDATA"));
        if (localAppData != null) {
            roots.add(new File(new File(localAppData, "Programs"), "Eclipse Adoptium"));
            roots.add(new File(new File(localAppData, "Programs"), "Microsoft"));
        }
        File home = file(System.getProperty("user.home"));
        if (home != null) {
            roots.add(new File(home, ".jdks"));
            roots.add(new File(new File(home, "scoop"), "apps"));
            roots.add(new File(new File(new File(home, ".sdkman"), "candidates"), "java"));
        }
        roots.add(new File("/usr/lib/jvm"));
        roots.add(new File("/Library/Java/JavaVirtualMachines"));
        return roots;
    }

    private static void add(Map<String, File> found, File location) {
        File executable = executable(location);
        if (executable != null) {
            // Links (/usr/bin/java, alternatives) are asked once; the first place stays.
            String key;
            try {
                key = executable.getCanonicalPath();
            } catch (IOException e) {
                key = executable.getAbsolutePath();
            }
            key = key.toLowerCase(Locale.ROOT);
            if (!found.containsKey(key)) {
                found.put(key, executable);
            }
        }
    }

    /** The {@code java} executable of an installation folder, or the file itself if it is one. */
    static File executable(File location) {
        if (location == null) {
            return null;
        }
        if (location.isFile()) {
            return location.getAbsoluteFile();
        }
        if (location.isDirectory()) {
            for (String name : new String[]{"java.exe", "java"}) {
                File candidate = new File(new File(location, "bin"), name);
                if (candidate.isFile()) {
                    return candidate.getAbsoluteFile();
                }
            }
        }
        return null;
    }

    /** The feature version of an installation (8, 17, 21 ...), or -1 if it cannot be found out. */
    int majorVersion(File executable) {
        // An installation says its version in the file "release": that spares starting it. If the file
        // names another version, the installation is out; without the file (a shim on the PATH) it is asked.
        File release = new File(executable.getParentFile().getParentFile(), "release");
        if (release.isFile()) {
            try {
                Matcher version = RELEASE_VERSION.matcher(new String(Files.readAllBytes(release.toPath()), "UTF-8"));
                if (version.find() && majorOf(version.group(1)) != wanted) {
                    return majorOf(version.group(1));
                }
            } catch (IOException | RuntimeException ignored) {
                // then the installation is asked
            }
        }
        return ask(executable);
    }

    private static int ask(File executable) {
        File output = null;
        Process process = null;
        try {
            output = File.createTempFile("java-probe", ".txt");
            process = new ProcessBuilder(Arrays.asList(executable.getPath(), "-XshowSettings:properties", "-version"))
                    .redirectErrorStream(true).redirectOutput(output).start();
            if (!process.waitFor(PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return -1;
            }
            return parse(new String(Files.readAllBytes(output.toPath()), Charset.defaultCharset()));
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (output != null && !output.delete()) {
                output.deleteOnExit();
            }
        }
    }

    /** The feature version in what {@code java -XshowSettings:properties -version} printed, or -1. */
    static int parse(String output) {
        Matcher property = VERSION_PROPERTY.matcher(output);
        if (property.find()) {
            return majorOf(property.group(1));
        }
        Matcher quoted = QUOTED_VERSION.matcher(output);
        return quoted.find() ? majorOf(quoted.group(1)) : -1;
    }

    /** "1.8.0_392" is 8, "21.0.5" is 21, "22-ea" is 22; -1 for anything else. */
    static int majorOf(String version) {
        if (version == null) {
            return -1;
        }
        String[] parts = version.trim().split("[^0-9]+");
        try {
            if (parts.length > 1 && parts[0].equals("1")) {
                return Integer.parseInt(parts[1]);
            }
            return parts.length > 0 && !parts[0].isEmpty() && parts[0].length() < 5 ? Integer.parseInt(parts[0]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private File remembered() {
        if (memory == null || !memory.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(memory)) {
            properties.load(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return file(properties.getProperty(memoryKey));
    }

    /** A failure only means that the search runs again next time. */
    private void remember(File executable) {
        if (memory == null) {
            return;
        }
        Properties properties = new Properties();
        try {
            if (memory.isFile()) {
                try (InputStream in = new FileInputStream(memory)) {
                    properties.load(in);
                }
            }
            properties.setProperty(memoryKey, executable.getPath());
            File folder = memory.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                return;
            }
            try (OutputStream out = new FileOutputStream(memory)) {
                properties.store(out, "Space Harvester launcher");
            }
        } catch (IOException | RuntimeException ignored) {
            // not remembered
        }
    }

    private static List<File> directories(File root) {
        List<File> result = new ArrayList<File>();
        File[] children = root == null ? null : root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    result.add(child);
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    private static File file(String text) {
        return text == null || text.trim().isEmpty() ? null : new File(text.trim());
    }
}
