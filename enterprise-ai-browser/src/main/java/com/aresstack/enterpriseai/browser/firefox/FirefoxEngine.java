package com.aresstack.enterpriseai.browser.firefox;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.BrowserEngine;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts Firefox on a chat profile. One process per profile: a profile already in use (by this or another
 * application instance) is refused instead of being opened a second time, which would only add a tab to the
 * running browser.
 */
public final class FirefoxEngine implements BrowserEngine {

    /** Line Firefox prints on stderr once WebDriver BiDi accepts connections. */
    static final String READY_MARKER = "WebDriver BiDi listening on ";
    static final String LOCK_FILE = ".enterprise-ai-browser.lock";
    private static final String USER_JS = "user.js";

    private final Path executable;
    private final long startTimeoutMillis;

    /**
     * @param executable         the Firefox executable
     * @param startTimeoutMillis how long to wait for BiDi readiness
     */
    public FirefoxEngine(Path executable, long startTimeoutMillis) {
        if (executable == null) {
            throw new IllegalArgumentException("executable must not be null");
        }
        this.executable = executable;
        this.startTimeoutMillis = Math.max(1000L, startTimeoutMillis);
    }

    /**
     * The Firefox executable: {@code configured} if given, else the usual install locations of the platform, else
     * {@code null}. Environment and OS name come from the composition root, which alone reads configuration.
     *
     * @param environment the process environment ({@code ProgramFiles}, {@code PATH}, …)
     * @param osName      the {@code os.name} system property
     */
    public static Path locate(String configured, java.util.Map<String, String> environment, String osName) {
        if (configured != null && !configured.trim().isEmpty()) {
            Path path = Paths.get(configured.trim());
            return Files.isRegularFile(path) ? path : null;
        }
        List<Path> candidates = new ArrayList<Path>();
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String variable : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = environment.get(variable);
                if (base != null) {
                    candidates.add(Paths.get(base, "Mozilla Firefox", "firefox.exe"));
                }
            }
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/Applications/Firefox.app/Contents/MacOS/firefox"));
        } else {
            String pathVariable = environment.get("PATH");
            if (pathVariable != null) {
                for (String directory : pathVariable.split(File.pathSeparator)) {
                    if (!directory.isEmpty()) {
                        candidates.add(Paths.get(directory, "firefox"));
                    }
                }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public BrowserInstance start(Path profileDirectory) throws BrowserException {
        ProfileLock lock = ProfileLock.acquire(profileDirectory);
        Process process = null;
        try {
            writeDefaultsOnce(profileDirectory);
            int port = freeLoopbackPort();
            List<String> command = new ArrayList<String>();
            command.add(executable.toString());
            command.add("-profile");
            command.add(profileDirectory.toAbsolutePath().toString());
            command.add("-no-remote");
            command.add("--remote-debugging-port=" + port);
            ProcessBuilder builder = new ProcessBuilder(command);
            // Java 8 has no Redirect.DISCARD: send stdout to the null device so it never blocks Firefox.
            builder.redirectOutput(new File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null"));
            process = builder.start();
            closeQuietly(process.getOutputStream());
            awaitReady(process, port);
            return new FirefoxInstance(process, port, lock);
        } catch (IOException e) {
            stop(process);
            lock.release();
            throw new BrowserException(BrowserException.Kind.START_FAILED,
                    "Firefox konnte nicht gestartet werden: " + e.getMessage(), e);
        } catch (BrowserException e) {
            stop(process);
            lock.release();
            throw e;
        } catch (RuntimeException e) {
            stop(process);
            lock.release();
            throw e;
        }
    }

    private void awaitReady(final Process process, int port) throws BrowserException {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> endpoint = new AtomicReference<String>();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        int at = line.indexOf(READY_MARKER);
                        if (at >= 0 && endpoint.get() == null) {
                            endpoint.set(line.substring(at + READY_MARKER.length()).trim());
                            ready.countDown();
                        }
                    }
                } catch (IOException ignored) {
                    // Stream closes with the process.
                }
                ready.countDown();
            }
        }, "firefox-stderr-" + port);
        reader.setDaemon(true);
        reader.start();
        try {
            boolean signalled = ready.await(startTimeoutMillis, TimeUnit.MILLISECONDS);
            if (!process.isAlive()) {
                throw new BrowserException(BrowserException.Kind.START_FAILED,
                        "Firefox wurde beim Start beendet (Exit-Code " + process.exitValue() + ")");
            }
            if (!signalled || endpoint.get() == null) {
                throw new BrowserException(BrowserException.Kind.TIMEOUT,
                        "Firefox hat WebDriver BiDi nicht rechtzeitig bereitgestellt");
            }
            if (!endpoint.get().contains("127.0.0.1:" + port) && !endpoint.get().contains("localhost:" + port)) {
                throw new BrowserException(BrowserException.Kind.START_FAILED,
                        "Firefox lauscht nicht auf dem erwarteten Loopback-Port");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException(BrowserException.Kind.START_FAILED, "Start von Firefox unterbrochen", e);
        }
    }

    /**
     * Writes quiet defaults (no welcome page, no default-browser question) once; the user may change them in the
     * profile afterwards.
     */
    static void writeDefaultsOnce(Path profileDirectory) throws IOException {
        Path userJs = profileDirectory.resolve(USER_JS);
        if (Files.exists(userJs)) {
            return;
        }
        String prefs = "user_pref(\"browser.shell.checkDefaultBrowser\", false);\n"
                + "user_pref(\"browser.aboutwelcome.enabled\", false);\n"
                + "user_pref(\"browser.startup.homepage_override.mstone\", \"ignore\");\n"
                + "user_pref(\"datareporting.policy.dataSubmissionPolicyBypassNotification\", true);\n"
                + "user_pref(\"toolkit.telemetry.reportingpolicy.firstRun\", false);\n";
        Files.write(userJs, prefs.getBytes(StandardCharsets.UTF_8));
    }

    static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void stop(Process process) {
        if (process != null) {
            process.destroy();
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Nothing to send to Firefox anyway.
        }
    }

    /** Exclusive lock on the profile for as long as our Firefox runs on it. */
    static final class ProfileLock {

        private final RandomAccessFile file;
        private final FileLock lock;

        private ProfileLock(RandomAccessFile file, FileLock lock) {
            this.file = file;
            this.lock = lock;
        }

        static ProfileLock acquire(Path profileDirectory) throws BrowserException {
            RandomAccessFile file = null;
            try {
                Files.createDirectories(profileDirectory);
                file = new RandomAccessFile(profileDirectory.resolve(LOCK_FILE).toFile(), "rw");
                FileLock lock = file.getChannel().tryLock();
                if (lock == null) {
                    file.close();
                    throw inUse();
                }
                return new ProfileLock(file, lock);
            } catch (OverlappingFileLockException e) {
                closeQuietly(file);
                throw inUse();
            } catch (IOException e) {
                closeQuietly(file);
                throw new BrowserException(BrowserException.Kind.START_FAILED,
                        "Browserprofil nicht nutzbar: " + e.getMessage(), e);
            }
        }

        private static BrowserException inUse() {
            return new BrowserException(BrowserException.Kind.START_FAILED,
                    "Das Browserprofil dieses Chats wird schon von einem anderen Programmfenster benutzt");
        }

        private static void closeQuietly(RandomAccessFile file) {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // Lock is gone with the file.
                }
            }
        }

        void release() {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Released with the file below.
            }
            closeQuietly(file);
        }
    }
}
