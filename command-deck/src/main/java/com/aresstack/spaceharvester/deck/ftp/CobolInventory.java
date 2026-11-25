package com.aresstack.spaceharvester.deck.ftp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Discover source candidates without retrieving or modifying their contents. */
public final class CobolInventory {
    public interface Source {
        List<String> descendants(String home) throws IOException, InterruptedException;
        List<RemoteEntry> members(String dataset) throws IOException, InterruptedException;
    }

    public record Member(String path, boolean programCandidate, boolean copybookCandidate) { }

    public record Progress(String phase, String dataset, int completed, int total) { }

    public record Result(CobolAnalysisArea area, List<Member> members, Map<String, String> failures,
                         List<String> excludedDatasets, boolean cancelled) {
        public Result {
            members = List.copyOf(members);
            failures = Map.copyOf(failures);
            excludedDatasets = List.copyOf(excludedDatasets);
        }

        public boolean complete() {
            return !cancelled && failures.isEmpty();
        }
    }

    private final Source source;

    public CobolInventory(Source source) {
        this.source = java.util.Objects.requireNonNull(source);
    }

    public static CobolInventory forSession(FtpSession session) {
        if (!session.mvs() || session.state() != ConnectionState.CONNECTED) {
            throw new IllegalStateException("Keine aktive MVS-Verbindung");
        }
        return new CobolInventory(new Source() {
            @Override
            public List<String> descendants(String home) throws IOException, InterruptedException {
                return await(session.files(files -> files.listAnalysisDatasets(home)));
            }

            @Override
            public List<RemoteEntry> members(String dataset) throws IOException, InterruptedException {
                return await(session.files(files -> files.listCopybookMembers(dataset)));
            }
        });
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future)
            throws IOException, InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(FtpSession.describe(error.getCause()), error.getCause());
        }
    }

    public Result scan(CobolAnalysisArea area, BooleanSupplier cancelled, Consumer<Progress> progress) {
        Set<String> programs = new LinkedHashSet<>();
        Set<String> copybooks = new LinkedHashSet<>();
        List<Member> members = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        String home = area.absoluteHome();
        programs.add(home);
        area.syslib().forEach(ds -> copybooks.add(MvsPath.absolute(ds)));
        boolean stopped = false;
        try {
            if (!cancelled.getAsBoolean()) {
                progress.accept(new Progress("Katalog", home, 0, 0));
                try {
                    for (String dataset : source.descendants(home)) {
                        if (!area.containsProgramDataset(dataset)) {
                            failures.put("Katalog " + home, "Eintrag außerhalb des Analysebereichs: " + dataset);
                            continue;
                        }
                        programs.add(MvsPath.absolute(MvsPath.unquote(dataset).toUpperCase(Locale.ROOT)));
                    }
                } catch (IOException error) {
                    failures.put("Katalog " + home, error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage());
                }
            }
            Set<String> datasets = new LinkedHashSet<>(programs);
            datasets.addAll(copybooks);
            int completed = 0;
            for (String dataset : datasets) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    stopped = true;
                    break;
                }
                progress.accept(new Progress("Members", dataset, completed, datasets.size()));
                try {
                    List<Member> found = new ArrayList<>();
                    for (RemoteEntry entry : source.members(dataset)) {
                        found.add(member(dataset, entry, programs.contains(dataset), copybooks.contains(dataset)));
                    }
                    members.addAll(found.stream().distinct().toList());
                } catch (FtpReplyException rejected) {
                    if (rejected.code() == 550 && !copybooks.contains(dataset)
                            && messageContains(rejected, "not a partitioned data set")) {
                        excluded.add(dataset);
                    } else if (!(dataset.equals(home) && programs.size() > 1 && !copybooks.contains(dataset)
                            && rejected.code() == 550 && messageContains(rejected, "no data sets found"))) {
                        failures.put(dataset, rejected.getMessage());
                    }
                } catch (IOException error) {
                    failures.put(dataset, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                }
                completed++;
                progress.accept(new Progress("Members", dataset, completed, datasets.size()));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
        return new Result(area, members, failures, excluded, stopped || cancelled.getAsBoolean());
    }

    private static boolean messageContains(FtpReplyException error, String text) {
        return error.getMessage() != null && error.getMessage().toLowerCase(Locale.ROOT).contains(text);
    }

    private static Member member(String dataset, RemoteEntry entry, boolean program, boolean copybook) throws IOException {
        if (entry.directory() || !MvsPath.isMember(entry.path())) {
            throw new IOException("Kein Member: " + entry.path());
        }
        String[] parts = MvsPath.splitMember(entry.path());
        if (!MvsPath.unquote(dataset).equalsIgnoreCase(parts[0]) || !MvsPath.isMemberName(parts[1])) {
            throw new IOException("Member außerhalb des Datasets: " + entry.path());
        }
        String path = MvsPath.absolute(MvsPath.memberSpec(parts[0].toUpperCase(Locale.ROOT),
                parts[1].toUpperCase(Locale.ROOT)));
        return new Member(path, program, copybook);
    }
}
