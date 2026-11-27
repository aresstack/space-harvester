package com.aresstack.askai.research.runtime.search;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #35, first slice: the repair coordinator ran with a NULL profile store, so every AI-repaired
 * SERP layout was learned, validated — and thrown away. The factory now opens the persistent
 * file-backed store (overridable path), creating its directory; an unusable path degrades loudly to
 * the old no-store behaviour instead of failing the search.
 */
public class LayoutProfileStoreWiringTest {

    @Test
    public void theConfiguredPathYieldsAPersistentStoreAndCreatesItsDirectory() throws Exception {
        File root = Files.createTempDirectory("askai-layout-profiles").toFile();
        File target = new File(new File(root, "deep"), "profiles.jsonl");
        String old = System.setProperty("askai.research.layoutProfiles", target.getAbsolutePath());
        try {
            assertNotNull("the store opens on the configured path",
                    LegacyBrowserSearchStrategyFactory.openProfileStore());
            assertTrue("the parent directory is created", target.getParentFile().isDirectory());
        } finally {
            if (old == null) {
                System.clearProperty("askai.research.layoutProfiles");
            } else {
                System.setProperty("askai.research.layoutProfiles", old);
            }
            deleteRecursively(root);
        }
    }

    @Test
    public void anUnusablePathDegradesToNoStoreInsteadOfFailingTheSearch() throws Exception {
        // A path whose parent is a FILE cannot host the store.
        File blocker = Files.createTempFile("askai-layout-blocker", ".tmp").toFile();
        String old = System.setProperty("askai.research.layoutProfiles",
                new File(blocker, "profiles.jsonl").getAbsolutePath());
        try {
            org.junit.Assert.assertNull("no store — repairs simply are not remembered",
                    LegacyBrowserSearchStrategyFactory.openProfileStore());
        } finally {
            if (old == null) {
                System.clearProperty("askai.research.layoutProfiles");
            } else {
                System.setProperty("askai.research.layoutProfiles", old);
            }
            blocker.delete();
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
