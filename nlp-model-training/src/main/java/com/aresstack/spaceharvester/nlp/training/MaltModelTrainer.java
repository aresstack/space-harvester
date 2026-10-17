package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Startet den MaltParser-Lernlauf in einer eigenen JVM mit dem vollständigen Klassenpfad dieses Moduls, fester
 * Heap-Grenze und Zeitlimit. Ergebnis ist {@code <configName>.mco} im Arbeitsverzeichnis; ein abgebrochener oder
 * fehlgeschlagener Lauf hinterlässt keine .mco.
 */
public final class MaltModelTrainer {

    private final Consumer<String> log;

    public MaltModelTrainer(Consumer<String> log) {
        this.log = log;
    }

    public record Result(Path mco, Duration duration, String heap, Optional<Long> peakRssMiB) {
    }

    public Result train(ModelRecipe recipe, Path trainFile, Path workDirectory, String heap) {
        ModelRecipe.Training training = recipe.training();
        Path mco = workDirectory.resolve(recipe.mcoFileName());
        try {
            Files.createDirectories(workDirectory);
            Files.deleteIfExists(mco);
            deleteTree(workDirectory.resolve(recipe.configName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> arguments = List.of(
                "-c", recipe.configName(),
                "-w", workDirectory.toAbsolutePath().toString(),
                "-i", trainFile.toAbsolutePath().toString(),
                "-if", "conllu",
                "-m", "learn",
                "-a", training.algorithm(),
                "-l", training.learner(),
                "-F", recipe.featureModel().toAbsolutePath().toString());
        Path logFile = workDirectory.resolve("training.log");
        long start = System.nanoTime();
        Optional<Long> peak = run(arguments, heap, Duration.ofMinutes(training.timeoutMinutes()), workDirectory, logFile);
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        if (!Files.isRegularFile(mco)) {
            throw new IllegalStateException("MaltParser hat keine " + mco.getFileName() + " erzeugt, siehe " + logFile);
        }
        log.accept("Modell " + mco + " in " + duration.toSeconds() + " s");
        return new Result(mco, duration, heap, peak);
    }

    /** Führt {@code org.maltparser.Malt} mit den Argumenten aus; gibt den höchsten gemessenen Speicher (Linux) zurück. */
    static Optional<Long> run(List<String> arguments, String heap, Duration timeout, Path directory, Path logFile) {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-Xmx" + heap);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.maltparser.Malt");
        command.addAll(arguments);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long peak = -1;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                peak = Math.max(peak, residentKiB(process.pid()));
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly().waitFor();
                    throw new IllegalStateException("MaltParser nach " + timeout.toMinutes() + " min abgebrochen");
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("unterbrochen", e);
        }
        String output = readLog(logFile);
        // MaltParser meldet manche Fehler nur in der Ausgabe und endet trotzdem mit 0
        if (process.exitValue() != 0 || output.contains("Out of memory") || output.contains("MaltChainedException")) {
            throw new IllegalStateException("MaltParser fehlgeschlagen (Exit " + process.exitValue() + "), siehe "
                    + logFile + ":\n" + tail(output));
        }
        return peak < 0 ? Optional.empty() : Optional.of(peak / 1024);
    }

    /** VmHWM aus /proc (Spitzenwert seit Start); auf anderen Systemen -1. */
    private static long residentKiB(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status");
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("\\D", ""));
                }
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return -1;
    }

    private static String readLog(Path logFile) {
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String tail(String output) {
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 15), lines.size()));
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
