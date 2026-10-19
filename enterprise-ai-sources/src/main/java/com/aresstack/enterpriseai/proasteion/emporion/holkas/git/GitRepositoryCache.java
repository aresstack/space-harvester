package com.aresstack.enterpriseai.proasteion.emporion.holkas.git;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.SubmoduleConfig.FetchRecurseSubmodulesMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The application-owned copy of the remotes (#137): one bare repository per remote and ref below the cache folder,
 * never a user's working tree. A fetch brings exactly one ref into {@link #SNAPSHOT_REF} (no tags, no submodules, no
 * checkout, so no hooks, filters or LFS downloads run); the first fetch transfers the history of that ref once, later
 * fetches only what is new. Nothing is ever pushed. The source reads files from the tree of the pinned commit.
 */
final class GitRepositoryCache {

    /** The local ref holding the fetched commit of the configured ref. */
    static final String SNAPSHOT_REF = "refs/eai/snapshot";
    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-fA-F]{40}");
    /** HTTP status in a JGit message; whole numbers only, so digits in a path never count. */
    private static final Pattern STATUS_DENIED = Pattern.compile("(?<![0-9])40[13](?![0-9])");
    private static final Pattern STATUS_NOT_FOUND = Pattern.compile("(?<![0-9])404(?![0-9])");

    private final Path root;
    private final Map<Path, Object> locks = new ConcurrentHashMap<Path, Object>();

    GitRepositoryCache(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /** The cache folder of a remote and ref. */
    Path directoryOf(GitRemote remote, String ref) {
        return root.resolve(hash(remote.uri() + "\n" + ref));
    }

    /** The snapshot fetched last, without network access; {@code null} if nothing has been fetched yet. */
    GitSnapshot cached(GitRemote remote, String ref) throws KnowledgeSourceException {
        Path directory = directoryOf(remote, ref);
        if (!Files.isDirectory(directory)) {
            return null;
        }
        synchronized (lock(directory)) {
            try (Repository repository = open(directory)) {
                Ref snapshot = repository.exactRef(SNAPSHOT_REF);
                String remoteRef = repository.getConfig().getString("eai", null, "remoteRef");
                if (snapshot == null || remoteRef == null) {
                    return null;
                }
                return snapshot(repository, snapshot.getObjectId(), remoteRef, ref);
            } catch (IOException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Cache nicht lesbar: " + directory, e);
            }
        }
    }

    /**
     * Fetch the ref from the remote and pin its commit.
     *
     * @param ref       branch, tag or full commit id; empty for the remote's default branch
     * @param transport configures each connection (credentials, proxy, TLS); may be {@code null}
     */
    GitSnapshot fetch(GitRemote remote, String ref, TransportConfigCallback transport, int timeoutSeconds)
            throws KnowledgeSourceException {
        Path directory = directoryOf(remote, ref);
        synchronized (lock(directory)) {
            try {
                Files.createDirectories(directory);
            } catch (IOException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Cache nicht anlegbar: " + directory, e);
            }
            try (Repository repository = open(directory); Git git = new Git(repository)) {
                String commit = COMMIT_ID.matcher(ref).matches() ? ref.toLowerCase(Locale.ROOT) : null;
                if (commit != null) {
                    return fetchCommit(repository, git, remote, commit, ref, transport, timeoutSeconds);
                }
                String remoteRef = remoteRef(remote, ref, transport, timeoutSeconds);
                fetch(git, remote, transport, timeoutSeconds, new RefSpec("+" + remoteRef + ":" + SNAPSHOT_REF));
                Ref fetched = repository.exactRef(SNAPSHOT_REF);
                if (fetched == null) {
                    throw new KnowledgeSourceException(Kind.NOT_FOUND, "Ref " + remoteRef + " nicht gefunden in "
                            + remote);
                }
                // An annotated tag points at a tag object: pin the commit it names.
                ObjectId target = peel(repository, fetched.getObjectId());
                pin(repository, target);
                StoredConfig config = repository.getConfig();
                config.setString("eai", null, "remoteRef", remoteRef);
                config.save();
                return snapshot(repository, target, remoteRef, ref);
            } catch (KnowledgeSourceException e) {
                throw e;
            } catch (InvalidRemoteException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Repository nicht erreichbar: " + remote);
            } catch (TransportException e) {
                throw transportFailure(remote, e);
            } catch (GitAPIException | IOException | RuntimeException e) {
                throw new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Fetch von " + remote + " gescheitert ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * A fixed commit: fetched once with all branches and tags of the remote (a commit may live on any of them, not
     * only on the default branch); later calls find it in the cache and do not touch the network again.
     */
    private static GitSnapshot fetchCommit(Repository repository, Git git, GitRemote remote, String commit, String ref,
                                           TransportConfigCallback transport, int timeoutSeconds)
            throws GitAPIException, IOException, KnowledgeSourceException {
        ObjectId target = ObjectId.fromString(commit);
        if (!repository.getObjectDatabase().has(target)) {
            fetch(git, remote, transport, timeoutSeconds,
                    new RefSpec("+" + Constants.R_HEADS + "*:refs/eai/heads/*"),
                    new RefSpec("+" + Constants.R_TAGS + "*:refs/eai/tags/*"));
        }
        if (!repository.getObjectDatabase().has(target)) {
            throw new KnowledgeSourceException(Kind.NOT_FOUND, "Commit " + commit + " ist in keinem Branch oder Tag "
                    + "von " + remote + " enthalten");
        }
        target = peel(repository, target);
        pin(repository, target);
        StoredConfig config = repository.getConfig();
        config.setString("eai", null, "remoteRef", commit);
        config.save();
        return snapshot(repository, target, commit, ref);
    }

    private static void fetch(Git git, GitRemote remote, TransportConfigCallback transport, int timeoutSeconds,
                              RefSpec... specs) throws GitAPIException {
        git.fetch()
                .setRemote(remote.uri())
                .setRefSpecs(specs)
                .setTagOpt(TagOpt.NO_TAGS)
                .setRecurseSubmodules(FetchRecurseSubmodulesMode.NO)
                .setRemoveDeletedRefs(false)
                .setTimeout(timeoutSeconds)
                .setTransportConfigCallback(transport)
                .call();
    }

    /** The commit an object names: itself, or the target of a (possibly nested) annotated tag. */
    private static ObjectId peel(Repository repository, ObjectId id) throws IOException, KnowledgeSourceException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevObject object = walk.parseAny(id);
            while (object instanceof RevTag) {
                object = walk.parseAny(((RevTag) object).getObject());
            }
            if (!(object instanceof RevCommit)) {
                throw new KnowledgeSourceException(Kind.UNSUPPORTED, "Ref zeigt nicht auf einen Commit: " + id.name());
            }
            return object.getId();
        }
    }

    /** Open the bare cache repository, read only for its users. */
    static Repository open(Path directory) throws IOException {
        Repository repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build();
        if (!repository.getObjectDatabase().exists()) {
            repository.create(true);
            StoredConfig config = repository.getConfig();
            // Nothing runs on this copy: no hooks folder in use, no submodule recursion, no automatic gc pauses.
            config.setString("core", null, "hooksPath", directory.resolve("no-hooks").toString());
            config.setString("fetch", null, "recurseSubmodules", "false");
            config.setInt("gc", null, "auto", 0);
            config.save();
        }
        // The repository config wins over the user's ~/.gitconfig: certificate checks stay on whatever is set there.
        StoredConfig config = repository.getConfig();
        if (!config.getBoolean("http", null, "sslVerify", true) || config.getString("http", null, "sslVerify") == null) {
            config.setBoolean("http", null, "sslVerify", true);
            config.save();
        }
        return repository;
    }

    private static void pin(Repository repository, ObjectId commit) throws IOException {
        RefUpdate update = repository.updateRef(SNAPSHOT_REF);
        update.setNewObjectId(commit);
        update.setForceUpdate(true);
        update.update();
    }

    private static GitSnapshot snapshot(Repository repository, ObjectId id, String remoteRef, String configuredRef)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            return new GitSnapshot(commit.getId().name(), commit.getTree().getId().name(), remoteRef,
                    configuredRef.isEmpty() ? shortName(remoteRef) : configuredRef,
                    commit.getCommitTime() * 1000L);
        }
    }

    /** The full name of the ref on the remote: the given branch or tag, or the remote's default branch. */
    private static String remoteRef(GitRemote remote, String ref, TransportConfigCallback transport, int timeout)
            throws GitAPIException, KnowledgeSourceException {
        LsRemoteCommand command = Git.lsRemoteRepository().setRemote(remote.uri()).setTimeout(timeout)
                .setTransportConfigCallback(transport).setHeads(false).setTags(false);
        Map<String, Ref> refs = command.callAsMap();
        if (ref.isEmpty()) {
            Ref head = refs.get(Constants.HEAD);
            if (head == null) {
                throw new KnowledgeSourceException(Kind.NOT_FOUND, "Repository " + remote
                        + " hat keinen Standard-Branch (leer?)");
            }
            if (head.isSymbolic()) {
                return head.getTarget().getName();
            }
            // Old servers without symref capability: the branch pointing at HEAD, main/master first.
            for (String candidate : new String[] {"refs/heads/main", "refs/heads/master"}) {
                Ref branch = refs.get(candidate);
                if (branch != null && head.getObjectId().equals(branch.getObjectId())) {
                    return candidate;
                }
            }
            for (Ref branch : refs.values()) {
                if (branch.getName().startsWith(Constants.R_HEADS) && head.getObjectId().equals(branch.getObjectId())) {
                    return branch.getName();
                }
            }
            return Constants.HEAD;
        }
        String[] candidates = ref.startsWith("refs/") ? new String[] {ref}
                : new String[] {Constants.R_HEADS + ref, Constants.R_TAGS + ref};
        for (String candidate : candidates) {
            if (refs.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new KnowledgeSourceException(Kind.NOT_FOUND, "Branch oder Tag „" + ref + "“ gibt es in " + remote
                + " nicht");
    }

    private static KnowledgeSourceException transportFailure(GitRemote remote, TransportException e) {
        // Without the remote itself: digits or words in its path must not look like a status.
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT)
                .replace(remote.uri().toLowerCase(Locale.ROOT), "");
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (message.contains("not authorized") || message.contains("authentication is required")
                || STATUS_DENIED.matcher(message).find() || message.contains("not permitted")
                || message.contains("authentication not supported")) {
            return new KnowledgeSourceException(Kind.ACCESS_DENIED, "Kein Lesezugriff auf " + remote
                    + " (Anmeldung abgelehnt oder Token ohne Berechtigung „Code (Read)“)");
        }
        if (root instanceof java.net.SocketTimeoutException || message.contains("timed out")
                || message.contains("timeout")) {
            return new KnowledgeSourceException(Kind.UNAVAILABLE, "Zeitüberschreitung bei " + remote);
        }
        if (root instanceof java.net.UnknownHostException) {
            return new KnowledgeSourceException(Kind.UNAVAILABLE, "Host unbekannt: " + remote);
        }
        if (message.contains("not found") || STATUS_NOT_FOUND.matcher(message).find()
                || message.contains("does not appear")) {
            return new KnowledgeSourceException(Kind.NOT_FOUND, "Repository nicht gefunden: " + remote);
        }
        return new KnowledgeSourceException(Kind.UNAVAILABLE, "Git-Repository nicht erreichbar: " + remote + " ("
                + root.getClass().getSimpleName() + ")");
    }

    private static String shortName(String remoteRef) {
        return Repository.shortenRefName(remoteRef);
    }

    private Object lock(Path directory) {
        Object lock = locks.get(directory);
        if (lock == null) {
            Object created = new Object();
            lock = locks.putIfAbsent(directory, created);
            if (lock == null) {
                lock = created;
            }
        }
        return lock;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
