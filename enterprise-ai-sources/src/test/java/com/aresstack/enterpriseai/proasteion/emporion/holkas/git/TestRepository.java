package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** A bare repository built in memory for tests: commits of whole file maps, no working tree, no network. */
final class TestRepository implements AutoCloseable {

    /** Marks a submodule entry (gitlink) in a file map. */
    static final String GITLINK = "\u0000gitlink";
    /** Marks a symbolic link entry in a file map; the rest of the value is the link target. */
    static final String SYMLINK = "\u0000symlink:";

    final Path directory;
    private final Repository repository;

    TestRepository(Path directory) throws IOException {
        this.directory = directory;
        this.repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build();
        repository.create(true);
    }

    /** Commit the complete file map as the new state of {@code branch}. */
    String commit(String branch, Map<String, ?> files) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, ?> file : new TreeMap<String, Object>(files).entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                Object value = file.getValue();
                if (GITLINK.equals(value)) {
                    entry.setFileMode(FileMode.GITLINK);
                    entry.setObjectId(ObjectId.fromString("0123456789abcdef0123456789abcdef01234567"));
                } else if (value instanceof String && ((String) value).startsWith(SYMLINK)) {
                    entry.setFileMode(FileMode.SYMLINK);
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, ((String) value).substring(SYMLINK.length())
                            .getBytes(StandardCharsets.UTF_8)));
                } else {
                    byte[] bytes = value instanceof byte[] ? (byte[]) value
                            : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, bytes));
                }
                builder.add(entry);
            }
            builder.finish();
            ObjectId tree = index.writeTree(inserter);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree);
            PersonIdent author = new PersonIdent("Test", "test@example.invalid");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage("state");
            Ref head = repository.exactRef(Constants.R_HEADS + branch);
            if (head != null) {
                commit.setParentId(head.getObjectId());
            }
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(Constants.R_HEADS + branch);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            update.update();
            return id.name();
        }
    }

    void tag(String name, String commit) throws IOException {
        RefUpdate update = repository.updateRef(Constants.R_TAGS + name);
        update.setNewObjectId(ObjectId.fromString(commit));
        update.update();
    }

    /** An annotated tag: the ref points at a tag object, not at the commit. */
    void annotatedTag(String name, String commit) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            TagBuilder tag = new TagBuilder();
            tag.setObjectId(ObjectId.fromString(commit), Constants.OBJ_COMMIT);
            tag.setTag(name);
            tag.setTagger(new PersonIdent("Test", "test@example.invalid"));
            tag.setMessage("release " + name);
            ObjectId id = inserter.insert(tag);
            inserter.flush();
            RefUpdate update = repository.updateRef(Constants.R_TAGS + name);
            update.setNewObjectId(id);
            update.update();
        }
    }

    /** Point HEAD of the remote at {@code branch} (the default branch). */
    void defaultBranch(String branch) throws IOException {
        repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch);
    }

    @Override
    public void close() {
        repository.close();
    }
}
