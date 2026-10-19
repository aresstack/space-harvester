package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeDocument;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import com.aresstack.enterpriseai.source.api.NavigationChildren;
import com.aresstack.enterpriseai.source.api.NavigationNode;
import com.aresstack.enterpriseai.source.api.PagePosition;
import com.aresstack.enterpriseai.source.api.SourceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitKnowledgeSourceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private TestRepository remote;
    private Path cache;
    private Map<String, Object> files;

    @Before
    public void createRemote() throws Exception {
        remote = new TestRepository(folder.newFolder("remote.git").toPath());
        cache = folder.getRoot().toPath().resolve("cache");
        files = new LinkedHashMap<String, Object>();
        files.put("README.md", "# Shop\n\nComponents: [Button](web/src/Button.tsx)\n");
        files.put("build.gradle", "plugins { id 'java' }\n");
        files.put("src/main/java/shop/Cart.java", "package shop;\n\nclass Cart {\n    int size() {\n        return 0;\n    }\n}\n");
        files.put("go.mod", "module example.com/shop\n");
        files.put("svc/cart.go", "package svc\n\nfunc Total() int { return 1 }\n");
        files.put("web/src/Button.tsx", "export const Button = () => <button>```ok```</button>;\n");
        files.put("web/src/App.jsx", "export default function App() { return null; }\n");
        files.put("web/src/api.ts", "export const url = '/api';\n");
        files.put("web/package.json", "{\"name\": \"web\"}\n");
        files.put("web/package-lock.json", "{}\n");
        files.put("web/node_modules/react/index.js", "module.exports = {};\n");
        files.put("design/tokens.json", "{\"color\": {\"primary\": \"#0055ff\"}}\n");
        files.put("design/logo.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>\n");
        files.put("design/hero.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        files.put("design/app.fig", new byte[] {'f', 'i', 'g', 0, 1});
        files.put("docs/manual.pdf", "%PDF-1.4 fake");
        files.put("docs/big.md", new String(new char[3 * 1024 * 1024]).replace('\0', 'x'));
        files.put("video.bin", new byte[] {0, 1, 2});
        files.put("tool.zip", new byte[] {'P', 'K', 3, 4});
        files.put("assets/large.psd.md",
                "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 123456\n");
        files.put("lib/shared", TestRepository.GITLINK);
        files.put("escape.md", TestRepository.SYMLINK + "../../etc/passwd");
        files.put("weird.java", "class Weird {\0}");
        remote.commit("main", files);
        remote.defaultBranch("main");
    }

    @After
    public void closeRemote() {
        remote.close();
    }

    @Test
    public void discoversMultiLanguageFilesAndSkipsWhatIsNoKnowledge() throws Exception {
        GitKnowledgeSource source = open();
        List<String> paths = paths(source.discover(SourceScope.builder().startPoint(".").maxDepth(30).build()));

        for (String expected : Arrays.asList("README.md", "build.gradle", "src/main/java/shop/Cart.java", "go.mod",
                "svc/cart.go", "web/src/Button.tsx", "web/src/App.jsx", "web/src/api.ts", "web/package.json",
                "design/tokens.json", "design/logo.svg", "design/hero.png", "design/app.fig", "docs/manual.pdf")) {
            assertTrue(expected + " missing in " + paths, paths.contains(expected));
        }
        for (String skipped : Arrays.asList("web/package-lock.json", "web/node_modules/react/index.js",
                "docs/big.md", "video.bin", "tool.zip", "assets/large.psd.md", "lib/shared", "escape.md")) {
            assertFalse(skipped + " must be skipped", paths.contains(skipped));
        }
    }

    @Test
    public void codeKeepsIndentationInAFenceWithLanguagePathAndRevision() throws Exception {
        GitKnowledgeSource source = open();
        KnowledgeDocument document = source.load(source.idOf("src/main/java/shop/Cart.java"));

        assertTrue(document.text(), document.text().contains("```java\npackage shop;\n\nclass Cart {\n"
                + "    int size() {\n        return 0;\n    }\n}\n```"));
        assertTrue(document.text().contains("`src/main/java/shop/Cart.java` · main @ "));
        KnowledgeResource resource = document.resource();
        assertEquals("java", resource.metadata().get("git.language").orElse(null));
        assertEquals("src/main/java/shop/Cart.java", resource.metadata().get("git.path").orElse(null));
        assertEquals(remote.directory.toUri().toString().replaceAll("/+$", ""),
                resource.metadata().get("git.remote").orElse(null));
        assertEquals(resource.metadata().get("git.blob").orElse(null), resource.revision().version());

        // Backticks in the code cannot close the fence.
        assertTrue(source.load(source.idOf("web/src/Button.tsx")).text().contains("````tsx\n"));
    }

    @Test
    public void assetsBinariesLfsAndDocumentsAreHandledHonestly() throws Exception {
        GitKnowledgeSource source = open();
        assertTrue(source.load(source.idOf("design/app.fig")).text().contains("Figma-Originaldatei"));
        assertTrue(source.load(source.idOf("design/hero.png")).text().contains("Bilddatei (image/png"));
        assertTrue(source.load(source.idOf("assets/large.psd.md")).text().contains("Git-LFS-Verweis"));
        assertTrue(source.load(source.idOf("docs/manual.pdf")).text().contains("PDF text of manual.pdf"));
        assertKind(Kind.UNSUPPORTED, source, "weird.java");
        assertKind(Kind.UNSUPPORTED, source, "docs/big.md");
        assertKind(Kind.UNSUPPORTED, source, "lib/shared");
        assertKind(Kind.UNSUPPORTED, source, "escape.md");
        assertKind(Kind.NOT_FOUND, source, "web/node_modules/react/index.js");
        assertKind(Kind.NOT_FOUND, source, "../outside.md");
        assertKind(Kind.NOT_FOUND, source, ".git/config");
    }

    @Test
    public void revisionsFollowBlobsAcrossFetches() throws Exception {
        GitKnowledgeSource source = open();
        Map<String, String> before = revisions(source.discover(SourceScope.builder().startPoint(".").maxDepth(30).build()));

        Map<String, Object> next = new HashMap<String, Object>(files);
        next.put("svc/cart.go", "package svc\n\nfunc Total() int { return 2 }\n");
        next.put("web/src/Link.tsx", next.remove("web/src/App.jsx"));
        next.remove("design/tokens.json");
        remote.commit("main", next);

        Map<String, String> after = revisions(source.discover(SourceScope.builder().startPoint(".").maxDepth(30).build()));
        assertEquals(before.get("README.md"), after.get("README.md"));
        assertNotEquals(before.get("svc/cart.go"), after.get("svc/cart.go"));
        assertFalse(after.containsKey("web/src/App.jsx"));
        assertTrue(after.containsKey("web/src/Link.tsx"));
        assertFalse(after.containsKey("design/tokens.json"));
        assertKind(Kind.NOT_FOUND, source, "design/tokens.json");
        assertTrue(source.load(source.idOf("svc/cart.go")).text().contains("return 2"));
    }

    @Test
    public void aNewSourceReadsTheCacheWithoutTheRemote() throws Exception {
        open().discover(SourceScope.of("README.md"));
        Path moved = folder.getRoot().toPath().resolve("gone.git");
        java.nio.file.Files.move(remote.directory, moved);

        GitKnowledgeSource again = GitFixtures.open(GitFixtures.provider(cache), remote.directory);
        assertTrue(again.load(again.idOf("README.md")).text().contains("# Shop"));
        try {
            again.discover(SourceScope.of("."));
            fail("discover must fetch and fail without the remote");
        } catch (KnowledgeSourceException e) {
            assertTrue(e.kind() + ": " + e.getMessage(), e.kind() == Kind.UNAVAILABLE || e.kind() == Kind.NOT_FOUND);
        }
    }

    @Test
    public void branchesTagsAndCommitsAreSelectable() throws Exception {
        Map<String, Object> feature = new HashMap<String, Object>(files);
        feature.put("FEATURE.md", "# Feature\n");
        String commit = remote.commit("feature/x", feature);
        remote.tag("v1", commit);

        GitKnowledgeSource branch = open("ref", "feature/x");
        assertTrue(paths(branch.discover(SourceScope.of("."))).contains("FEATURE.md"));
        assertTrue(branch.idOf("FEATURE.md").value().contains("@feature/x/FEATURE.md"));
        assertTrue(paths(open("ref", "v1").discover(SourceScope.of("."))).contains("FEATURE.md"));
        assertFalse(paths(open().discover(SourceScope.of("."))).contains("FEATURE.md"));

        // A commit that only lives on a feature branch, not on the default branch.
        GitKnowledgeSource pinned = open("ref", commit);
        assertTrue(paths(pinned.discover(SourceScope.of("."))).contains("FEATURE.md"));
        assertTrue(pinned.load(pinned.idOf("FEATURE.md")).text().contains("# Feature"));

        // Annotated tags point at a tag object; the source reads the commit behind it.
        remote.annotatedTag("v2", commit);
        assertTrue(paths(open("ref", "v2").discover(SourceScope.of("."))).contains("FEATURE.md"));

        GitKnowledgeSource unknownCommit = open("ref", "0123456789abcdef0123456789abcdef01234567");
        try {
            unknownCommit.discover(SourceScope.of("."));
            fail("unknown commit");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
        }

        GitKnowledgeSource missing = open("ref", "nope");
        try {
            missing.discover(SourceScope.of("."));
            fail("missing branch");
        } catch (KnowledgeSourceException e) {
            assertEquals(Kind.NOT_FOUND, e.kind());
            assertTrue(e.getMessage().contains("nope"));
        }
    }

    @Test
    public void navigationShowsFoldersFirstAndBreadcrumbs() throws Exception {
        GitKnowledgeSource source = open();
        PagePosition position = source.position(source.idOf("web/src/Button.tsx"));
        List<String> crumbs = new ArrayList<String>();
        for (NavigationNode node : position.path()) {
            crumbs.add(node.title());
        }
        assertEquals(Arrays.asList(position.root().title(), "web", "src", "Button.tsx"), crumbs);

        NavigationChildren top = source.children(position.root(), "");
        List<String> names = new ArrayList<String>();
        for (NavigationNode node : top.nodes()) {
            names.add(node.title());
        }
        // "assets" only holds an LFS pointer: listed in the tree, explained on load, never indexed.
        assertEquals(Arrays.asList("assets", "design", "docs"), names.subList(0, 3));
        assertTrue(names.indexOf("web") < names.indexOf("README.md"));
        // The submodule itself is never listed, only its parent folder.
        assertTrue(source.children(top.nodes().get(names.indexOf("lib")), "").nodes().isEmpty());

        NavigationNode web = top.nodes().get(names.indexOf("web"));
        List<String> below = new ArrayList<String>();
        for (NavigationNode node : source.children(web, "").nodes()) {
            below.add(node.title());
        }
        assertEquals(Arrays.asList("src", "package.json"), below);

        KnowledgeDocument listing = source.load(web.resource());
        assertTrue(listing.text(), listing.text().contains("- [src/](src/)"));
        assertTrue(source.crossReferences(source.idOf("README.md")).outgoing().get(0).target()
                .equals(source.idOf("web/src/Button.tsx")));
    }

    @Test
    public void providerRejectsCredentialsInTheUrlAndUnsupportedProtocols() {
        GitSourceProvider provider = GitFixtures.provider(cache);
        assertEquals(1, provider.validate(settings("https://user:secret@dev.azure.com/o/p/_git/r")).size());
        assertFalse(provider.validate(settings("https://user:secret@dev.azure.com/o/p/_git/r")).get(0)
                .contains("secret"));
        assertEquals(1, provider.validate(settings("git@github.com:o/r.git")).size());
        assertEquals(1, provider.validate(settings("ssh://git@host/r.git")).size());
        assertTrue(provider.validate(settings("https://dev.azure.com/org/project/_git/repo")).isEmpty());
        assertEquals("dev.azure.com/org/project/_git/repo",
                provider.locator(settings("https://dev.azure.com/org/project/_git/repo")));
        assertEquals("github.com/o/r",
                provider.locator(settings("https://GitHub.com/o/r.git")));
    }

    private static com.aresstack.enterpriseai.astu.source.SourceSettings settings(String url) {
        Map<String, String> values = new HashMap<String, String>();
        values.put("url", url);
        return com.aresstack.enterpriseai.astu.source.SourceSettings.of(values);
    }

    private GitKnowledgeSource open(String... keyValues) {
        return GitFixtures.open(GitFixtures.provider(cache), remote.directory, keyValues);
    }

    private static void assertKind(Kind kind, GitKnowledgeSource source, String path) {
        try {
            source.load(KnowledgeResourceId.of(source.idOf("").value() + path));
            fail("expected " + kind + " for " + path);
        } catch (KnowledgeSourceException e) {
            assertEquals(path + ": " + e.getMessage(), kind, e.kind());
        }
    }

    private static List<String> paths(List<KnowledgeResource> resources) {
        List<String> paths = new ArrayList<String>();
        for (KnowledgeResource resource : resources) {
            paths.add(resource.metadata().get("git.path").orElse(null));
        }
        return paths;
    }

    private static Map<String, String> revisions(List<KnowledgeResource> resources) {
        Map<String, String> revisions = new HashMap<String, String>();
        for (KnowledgeResource resource : resources) {
            revisions.put(resource.metadata().get("git.path").orElse(null), resource.revision().version());
        }
        return revisions;
    }
}
