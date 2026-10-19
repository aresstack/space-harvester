package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResourceId;
import com.aresstack.enterpriseai.source.api.KnowledgeSourcePort;
import com.aresstack.enterpriseai.source.api.testing.KnowledgeSourceContractTest;
import org.junit.After;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.util.LinkedHashMap;
import java.util.Map;

/** The shared source contract against a repository built locally; links are relative Markdown links. */
public class GitKnowledgeSourceContractTest extends KnowledgeSourceContractTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private TestRepository remote;
    private GitKnowledgeSource last;

    @Override
    protected KnowledgeSourcePort createSource() throws Exception {
        if (remote == null) {
            remote = new TestRepository(folder.newFolder("remote.git").toPath());
            Map<String, Object> files = new LinkedHashMap<String, Object>();
            files.put("README.md", "# Demo\n\nSee [guide](docs/guide.md) and [missing](docs/missing.md).\n");
            files.put("docs/guide.md", "# Guide\n\nBack to [readme](../README.md).\n");
            files.put("src/Main.java", "class Main {\n    void run() {}\n}\n");
            remote.commit("main", files);
            remote.defaultBranch("main");
        }
        last = GitFixtures.open(GitFixtures.provider(folder.getRoot().toPath().resolve("cache")), remote.directory);
        return last;
    }

    @After
    public void closeRemote() {
        if (remote != null) {
            remote.close();
        }
    }

    @Override
    protected String linkedStartPoint() {
        return "README.md";
    }

    @Override
    protected String missingStartPoint() {
        return "nope/absent.md";
    }

    @Override
    protected KnowledgeResourceId unknownResourceId() {
        return last.idOf("docs/absent.md");
    }
}
