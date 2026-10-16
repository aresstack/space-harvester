package com.aresstack.enterpriseai.source.api.files;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** #120: only granted folders may be read; links and {@code ..} never widen a grant. */
public class LocalFileAccessPolicyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void withoutGrantNothingIsAllowed() throws IOException {
        Path file = temp.newFile("a.txt").toPath();
        assertFalse(LocalFileAccessPolicy.none().isAllowed(file));
        assertFalse(LocalFileAccessPolicy.of(false, Collections.<Path>emptyList()).isAllowed(file));
        assertFalse(LocalFileAccessPolicy.none().allowAll());
    }

    @Test
    public void aGrantedFolderAllowsItselfAndEverythingBelowButNoSiblingOrParent() throws IOException {
        Path granted = temp.newFolder("granted").toPath();
        Path nested = Files.createDirectories(granted.resolve("sub"));
        Path file = Files.createFile(nested.resolve("doc.txt"));
        Path sibling = temp.newFolder("grantedOther").toPath();
        LocalFileAccessPolicy policy = LocalFileAccessPolicy.of(false, Collections.singletonList(granted));

        assertTrue(policy.isAllowed(granted));
        assertTrue(policy.isAllowed(file));
        assertTrue(policy.isAllowed(nested.resolve("not-yet-there.txt")));
        assertFalse(policy.isAllowed(sibling));
        assertFalse(policy.isAllowed(temp.getRoot().toPath()));
        assertFalse(policy.isAllowed(granted.resolve("..").resolve("grantedOther")));
        assertFalse(policy.isAllowed(nested.resolve("../../grantedOther/x.txt")));
    }

    @Test
    public void severalSeparateRootsAreAllowed() throws IOException {
        Path one = temp.newFolder("one").toPath();
        Path two = temp.newFolder("two").toPath();
        Path three = temp.newFolder("three").toPath();
        LocalFileAccessPolicy policy = LocalFileAccessPolicy.of(false, Arrays.asList(one, two));

        assertTrue(policy.isAllowed(one.resolve("a.txt")));
        assertTrue(policy.isAllowed(two.resolve("b.txt")));
        assertFalse(policy.isAllowed(three.resolve("c.txt")));
        assertEquals(2, policy.roots().size());
    }

    @Test
    public void aLinkInsideAGrantToAnOutsideTargetIsRefused() throws IOException {
        Path granted = temp.newFolder("granted").toPath();
        Path secret = temp.newFolder("secret").toPath();
        Path secretFile = Files.createFile(secret.resolve("geheim.txt"));
        Path dirLink;
        Path fileLink;
        try {
            dirLink = Files.createSymbolicLink(granted.resolve("extern"), secret);
            fileLink = Files.createSymbolicLink(granted.resolve("link.txt"), secretFile);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("file system without symbolic links", e);
            return;
        }
        LocalFileAccessPolicy policy = LocalFileAccessPolicy.of(false, Collections.singletonList(granted));

        assertFalse(policy.isAllowed(dirLink));
        assertFalse(policy.isAllowed(dirLink.resolve("geheim.txt")));
        assertFalse(policy.isAllowed(fileLink));
        try {
            policy.requireAllowed(fileLink);
            fail("a link out of the grant must be refused");
        } catch (SecurityException expected) {
            // refused
        }
        assertTrue(policy.including(secret).isAllowed(fileLink));
    }

    @Test
    public void allowAllAllowsAnyPathAndIsOnlyOnWhenAsked() throws IOException {
        Path anywhere = temp.newFile("x.txt").toPath();
        assertTrue(LocalFileAccessPolicy.of(true, Collections.<Path>emptyList()).isAllowed(anywhere));
        assertFalse(LocalFileAccessPolicy.of(false, Collections.<Path>emptyList()).isAllowed(anywhere));
    }

    @Test
    public void aGrantNotCreatedYetCoversOnlyItsOwnPath() {
        Path unreachable = temp.getRoot().toPath().resolve("missing").resolve("deeper");
        LocalFileAccessPolicy policy = LocalFileAccessPolicy.of(false, Collections.singletonList(unreachable));
        // The grant itself does not exist: its nearest existing parent decides, which is the temp root.
        assertTrue(policy.isAllowed(unreachable.resolve("a.txt")));
        assertFalse(policy.isAllowed(temp.getRoot().toPath().resolve("missing2")));
    }
}
