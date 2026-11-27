package com.aresstack.spaceharvester.browser.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lasting profiles per key and browser, the lock, the temporary fallback and the crash sweep (moved from Ask AI). */
class ProfileLeaseTest {

    private static final long DAY = BrowserRuntimeConfig.DEFAULT_STALE_TEMPORARY_AGE.toMillis();

    @TempDir
    Path profiles;

    private ProfileLease lease(String key) {
        return ProfileLease.lasting(ProfileLease.keyedFolder(profiles, key), "firefox", 0L, DAY);
    }

    @Test
    void eachKeyOwnsItsLastingProfile() throws Exception {
        ProfileLease chatA = lease("chat-a");
        ProfileLease chatB = lease("chat-b");
        try {
            assertFalse(chatA.isTemporary());
            assertFalse(chatB.isTemporary());
            assertNotEquals(chatA.directory(), chatB.directory(), "chats never share a profile");
            assertEquals(ProfileLease.keyedFolder(profiles, "chat-a").resolve("firefox"), chatA.directory());
            assertFalse(chatA.directory().toString().contains("chat-a"), "no chat id in the path");
            Files.write(chatA.directory().resolve("cookies.sqlite"), new byte[]{1});
        } finally {
            chatA.close();
            chatB.close();
        }
        try (ProfileLease reopened = lease("chat-a")) {
            assertEquals(chatA.directory(), reopened.directory(), "the chat finds its profile again");
            assertTrue(Files.exists(reopened.directory().resolve("cookies.sqlite")), "its sign-ins survived");
        }
        try (ProfileLease other = lease("chat-b")) {
            assertFalse(Files.exists(other.directory().resolve("cookies.sqlite")), "chat B never sees chat A's cookies");
        }
    }

    @Test
    void twoBrowsersNeverShareAProfileFolder() {
        Path folder = profiles.resolve("host-managed");
        try (ProfileLease firefox = ProfileLease.lasting(folder, "firefox", 0L, DAY);
             ProfileLease edge = ProfileLease.lasting(folder, "msedge", 0L, DAY)) {
            assertFalse(firefox.isTemporary());
            assertFalse(edge.isTemporary());
            assertNotEquals(firefox.directory(), edge.directory());
            assertEquals(folder, firefox.directory().getParent(), "deleting the host's folder deletes the profile");
        }
    }

    @Test
    void aProfileFromBeforeThePerBrowserLayoutMovesIntoTheSubfolderOfItsKind() throws Exception {
        Path folder = Files.createDirectories(profiles.resolve("chat-from-yesterday"));
        Files.write(folder.resolve("Local State"), new byte[]{1});
        Files.createDirectories(folder.resolve("Default"));
        Files.write(folder.resolve("Default").resolve("Cookies"), new byte[]{2});
        try (ProfileLease firefox = ProfileLease.lasting(folder, "firefox", 0L, DAY)) {
            assertFalse(Files.exists(firefox.directory().resolve("Default")), "a Chromium profile never goes to Firefox");
        }
        try (ProfileLease edge = ProfileLease.lasting(folder, "msedge", 0L, DAY)) {
            assertTrue(Files.exists(edge.directory().resolve("Default").resolve("Cookies")), "the sign-ins stay");
            assertTrue(Files.exists(edge.directory().resolve("Local State")));
            assertFalse(Files.exists(folder.resolve("Default")));
            assertTrue(Files.isDirectory(folder.resolve("firefox")), "other browsers' folders stay where they are");
        }
        try (ProfileLease chrome = ProfileLease.lasting(folder, "chrome", 0L, DAY)) {
            assertFalse(Files.exists(chrome.directory().resolve("Default")), "moved once, to the first browser of its kind");
        }
    }

    @Test
    void aSecondBrowserOfTheSameProfileGetsATemporaryProfileThatIsDeletedOnClose() throws Exception {
        ProfileLease first = lease("chat-a");
        ProfileLease second = lease("chat-a");
        try {
            assertTrue(second.isTemporary());
            assertNotEquals(first.directory(), second.directory());
            assertEquals(ProfileLease.keyedFolder(profiles, "chat-a"), second.directory().getParent(),
                    "stays inside the profile's own folder");
            Files.write(second.directory().resolve("prefs.js"), new byte[]{1});
        } finally {
            second.close();
        }
        assertFalse(Files.exists(second.directory()), "temporary profile removed");
        first.close();
        assertTrue(Files.isDirectory(first.directory()), "the lasting profile stays");
        try (ProfileLease again = lease("chat-a")) {
            assertEquals(first.directory(), again.directory(), "released: the next browser owns the profile again");
        }
    }

    @Test
    void withoutAProfileThereIsNoSharedLastingProfile() {
        ProfileLease first = ProfileLease.temporary(profiles, "firefox", DAY);
        ProfileLease second = ProfileLease.temporary(profiles, "firefox", DAY);
        assertTrue(first.isTemporary());
        assertTrue(second.isTemporary());
        assertNotEquals(first.directory(), second.directory());
        first.close();
        second.close();
        assertFalse(Files.exists(first.directory()));
        assertFalse(Files.exists(profiles.resolve("firefox")), "no app-wide profile is created");
    }

    @Test
    void aRestartWaitsForTheTerminatedGenerationToReleaseItsProfile() throws Exception {
        Path folder = ProfileLease.keyedFolder(profiles, "chat-a");
        ProfileLease terminated = ProfileLease.lasting(folder, "firefox", 0L, DAY);
        Thread cutter = new Thread(() -> {
            try {
                Thread.sleep(300L); // the abandon thread closes Playwright, then releases the lease
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            terminated.close();
        });
        cutter.start();
        try (ProfileLease restarted = ProfileLease.lasting(folder, "firefox", 5_000L, DAY)) {
            assertFalse(restarted.isTemporary(), "the restart got its own profile, with its sign-ins");
            assertEquals(terminated.directory(), restarted.directory());
        } finally {
            cutter.join();
        }
    }

    @Test
    void temporaryProfilesLeftByACrashAreSwept() throws Exception {
        Path scratch = profiles.resolve("temporary");
        Path stale = Files.createDirectories(scratch.resolve("firefox-crashed"));
        Files.write(stale.resolve("cookies.sqlite"), new byte[]{1});
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - DAY - 60_000L));
        Path recent = Files.createDirectories(scratch.resolve("firefox-running"));

        ProfileLease.temporary(profiles, "firefox", DAY).close();

        assertFalse(Files.exists(stale), "an old temporary profile is removed");
        assertTrue(Files.isDirectory(recent), "a live browser's temporary profile is left alone");
    }
}
