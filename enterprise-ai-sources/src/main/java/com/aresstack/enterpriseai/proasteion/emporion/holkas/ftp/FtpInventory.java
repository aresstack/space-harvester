package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import com.aresstack.enterpriseai.astu.knowledge.KnowledgeResource;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.SourceScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

final class FtpInventory {

    @FunctionalInterface
    interface ResourceFactory {
        KnowledgeResource create(String path, String name, long modifiedMillis);
    }

    private final FtpSessionPool<FtpClientSession> sessions;
    private final FtpSourceEligibility eligibility;

    FtpInventory(FtpSessionPool<FtpClientSession> sessions, FtpSourceEligibility eligibility) {
        this.sessions = sessions;
        this.eligibility = eligibility;
    }

    List<KnowledgeResource> discover(SourceScope scope, ResourceFactory resources) throws KnowledgeSourceException {
        if (scope.startPoints().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        boolean mvs = sessions.run(FtpClientSession::mvs);
        List<String> starts = normalizeStartPoints(scope, mvs);
        Map<String, KnowledgeResource> found = new LinkedHashMap<String, KnowledgeResource>();
        Set<String> visited = new LinkedHashSet<String>();
        Set<String> examined = new LinkedHashSet<String>();
        Deque<Location> pending = new ArrayDeque<Location>();
        for (String path : starts) {
            pending.add(new Location(path, 0, true));
        }
        while (!pending.isEmpty() && found.size() < scope.maxResources()) {
            checkpoint();
            Location location = pending.removeFirst();
            if (!visited.add(key(location.path, mvs))) {
                continue;
            }
            if (mvs && new MvsPathDialect().isMemberPath(location.path)) {
                add(found, examined, resources, mvs, location.path, name(location.path, mvs), 0L);
                continue;
            }
            List<FtpClientSession.FtpEntry> children = sessions.run(session -> session.list(location.path));
            if (children.isEmpty() && ((!mvs && location.start) || (mvs
                    && sessions.run(session -> session.isSequentialDataset(location.path))))) {
                add(found, examined, resources, mvs, location.path, name(location.path, mvs), 0L);
            }
            for (FtpClientSession.FtpEntry child : children) {
                checkpoint();
                if (found.size() >= scope.maxResources()) {
                    break;
                }
                if (!child.directory()) {
                    add(found, examined, resources, mvs, child.path(), child.name(), child.modifiedMillis());
                } else if (location.depth < scope.maxDepth()) {
                    pending.add(new Location(child.path(), location.depth + 1, false));
                } else if (mvs && sessions.run(session -> session.isSequentialDataset(child.path()))) {
                    add(found, examined, resources, mvs, child.path(), child.name(), child.modifiedMillis());
                }
            }
        }
        return new ArrayList<KnowledgeResource>(found.values());
    }

    private static List<String> normalizeStartPoints(SourceScope scope, boolean mvs) throws KnowledgeSourceException {
        List<String> starts = new ArrayList<String>();
        for (String point : scope.startPoints()) {
            String path = point.trim();
            if (mvs) {
                path = MvsQuoteNormalizer.unquote(path).toUpperCase(Locale.ROOT);
                if (path.endsWith(".*")) {
                    path = path.substring(0, path.length() - 2);
                }
                if (path.isEmpty() || path.equals("/") || MvsQuoteNormalizer.hasWildcard(path)
                        || path.indexOf('?') >= 0) {
                    throw new KnowledgeSourceException(KnowledgeSourceException.Kind.INVALID_RESPONSE,
                            "FTP-Synchronisierung benötigt einen konkreten HLQ oder Datasetnamen");
                }
            }
            if (!path.isEmpty()) {
                starts.add(path);
            }
        }
        return starts;
    }

    private static String name(String path, boolean mvs) {
        if (mvs) {
            int member = path.indexOf('(');
            if (member >= 0 && path.endsWith(")")) {
                return path.substring(member + 1, path.length() - 1);
            }
            return MvsDatasetLocator.of(path).displayName();
        }
        String stripped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return stripped.substring(stripped.lastIndexOf('/') + 1);
    }

    private void add(Map<String, KnowledgeResource> found, Set<String> examined, ResourceFactory resources, boolean mvs,
                            String path, String name, long modifiedMillis) throws KnowledgeSourceException {
        String idPath = mvs ? MvsQuoteNormalizer.unquote(path) : path.replaceFirst("^/+", "");
        if (idPath.isEmpty()) {
            return;
        }
        String identity = key(idPath, mvs);
        if (!examined.add(identity)) {
            return;
        }
        if (!sessions.run(session -> eligibility.eligible(session, idPath))) {
            return;
        }
        found.put(identity, resources.create(idPath, name, modifiedMillis));
    }

    private static String key(String path, boolean mvs) {
        return mvs ? MvsQuoteNormalizer.unquote(path).toUpperCase(Locale.ROOT) : path;
    }

    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("FTP inventory interrupted");
        }
    }

    private static final class Location {
        private final String path;
        private final int depth;
        private final boolean start;

        private Location(String path, int depth, boolean start) {
            this.path = path;
            this.depth = depth;
            this.start = start;
        }
    }
}