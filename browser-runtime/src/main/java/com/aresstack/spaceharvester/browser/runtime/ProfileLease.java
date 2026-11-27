package com.aresstack.spaceharvester.browser.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The profile ONE browser runs on, moved from Ask AI's Camoufox provisioning and generalised to every browser.
 *
 * <p>A lasting profile lies in its folder as {@code <folder>/<channel>} (cookies, consent answers and sign-ins
 * survive restarts; two browsers never mix a profile) and is locked while a browser uses it
 * ({@code <folder>/<channel>.lock}). A second browser of the SAME profile (a restart overlapping the old generation)
 * gets a temporary profile next to it that is deleted on {@link #close()}, since two browser processes can never
 * share one profile. A generation that was just terminated releases its lock on a background thread once its browser
 * has exited; the restart waits up to the configured release wait for that instead of falling back at once. Without
 * a profile the browser gets a temporary one. Temporary profiles left behind by a crash are swept.</p>
 */
final class ProfileLease implements AutoCloseable {

    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final boolean temporary;

    private ProfileLease(Path directory, FileChannel channel, FileLock lock, boolean temporary) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
        this.temporary = temporary;
    }

    Path directory() {
        return directory;
    }

    boolean isTemporary() {
        return temporary;
    }

    /**
     * The lasting profile of {@code browser} in {@code folder}, or a temporary one when another browser holds it.
     * A lock held by ANOTHER process fails at once; one held inside this JVM is retried until {@code releaseWaitMillis}.
     */
    static ProfileLease lasting(Path folder, String browser, long releaseWaitMillis, long staleMillis) {
        try {
            Files.createDirectories(folder);
            restrictToOwner(folder);
            FileChannel channel = FileChannel.open(folder.resolve(browser + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = lockReleasedWithin(channel, releaseWaitMillis);
            if (lock != null) {
                Path persistent = folder.resolve(browser);
                Files.createDirectories(persistent);
                return new ProfileLease(persistent, channel, lock, false);
            }
            channel.close();
            sweepStaleTemporaries(folder, browser + "-session-", staleMillis);
            return new ProfileLease(Files.createTempDirectory(folder, browser + "-session-"), null, null, true);
        } catch (IOException failed) {
            throw new UncheckedIOException("browser profile unavailable under " + folder, failed);
        }
    }

    /** A temporary profile under {@code <profiles>/temporary}, deleted on {@link #close()}. */
    static ProfileLease temporary(Path profiles, String browser, long staleMillis) {
        try {
            Path scratch = profiles.resolve("temporary");
            Files.createDirectories(scratch);
            sweepStaleTemporaries(scratch, browser + "-", staleMillis);
            return new ProfileLease(Files.createTempDirectory(scratch, browser + "-"), null, null, true);
        } catch (IOException failed) {
            throw new UncheckedIOException("temporary browser profile unavailable under " + profiles, failed);
        }
    }

    /** {@code <profiles>/chats/<first 16 hex digits of sha256(key)>}: no chat id in a path name. */
    static Path keyedFolder(Path profiles, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i] & 0xff));
            }
            return profiles.resolve("chats").resolve(hex.toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static FileLock lockReleasedWithin(FileChannel channel, long waitMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, waitMillis));
        while (true) {
            try {
                return channel.tryLock();
            } catch (OverlappingFileLockException sameJvm) {
                if (System.nanoTime() >= deadline) {
                    return null;
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } catch (IOException | RuntimeException busy) {
                return null;
            }
        }
    }

    /** Best effort: remove temporary profiles a crash left behind (a live one is never that old). */
    private static void sweepStaleTemporaries(Path parent, String prefix, long staleMillis) {
        long cutoff = System.currentTimeMillis() - staleMillis;
        try (Stream<Path> entries = Files.list(parent)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (name.startsWith(prefix) && !name.endsWith(".lock") && Files.isDirectory(entry)
                        && Files.getLastModifiedTime(entry).toMillis() < cutoff) {
                    deleteRecursively(entry);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Best effort: a file a browser still holds stays. Null does nothing. */
    static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void restrictToOwner(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows: the per-user profile folder's ACL protects the profile.
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
        if (temporary) {
            deleteRecursively(directory);
        }
    }
}
