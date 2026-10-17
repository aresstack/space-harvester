package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Einstieg der Gradle-Tasks ({@code build.gradle}): {@code prepare | validate | train | evaluate | package | verify},
 * jeweils mit {@code --recipe <properties> --treebank <Verzeichnis> --work <Verzeichnis> [--heap 6g]}. Jeder Schritt
 * liest nur, was die vorigen in {@code --work} abgelegt haben, und bricht mit Exit 1 ab, wenn etwas nicht stimmt.
 */
public final class TrainingCli {

    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private final ModelRecipe recipe;
    private final Path treebankDirectory;
    private final Path work;
    private final String heap;

    TrainingCli(ModelRecipe recipe, Path treebankDirectory, Path work, String heap) {
        this.recipe = recipe;
        this.treebankDirectory = treebankDirectory;
        this.work = work;
        this.heap = heap == null ? recipe.training().heap() : heap;
    }

    /** Was {@code train} über den Lauf festhält, als {@code training.json}. */
    record TrainingRun(String mcoSha256, String trainedAt, long durationSeconds, String heap, Long peakRssMiB,
                       String javaVersion, int sentences, int words, List<String> labels) {
    }

    public static void main(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        if (args.length == 0 || !options.containsKey("--recipe") || !options.containsKey("--work")) {
            System.err.println("Aufruf: TrainingCli prepare|validate|train|evaluate|package|verify "
                    + "--recipe <datei> --treebank <verzeichnis> --work <verzeichnis> [--heap 6g]");
            System.exit(2);
        }
        Path recipeFile = Path.of(options.get("--recipe"));
        Path work = Path.of(options.get("--work"));
        Path treebank = Path.of(options.getOrDefault("--treebank", work.resolve("treebank").toString()));
        try {
            new TrainingCli(ModelRecipe.load(recipeFile), treebank, work, options.get("--heap")).run(args[0]);
        } catch (RuntimeException e) {
            System.err.println("FEHLER: " + e.getMessage());
            System.exit(1);
        }
    }

    void run(String command) {
        switch (command) {
            case "prepare" -> new TreebankAcquisition(TrainingCli::log).acquire(recipe.treebank(), treebankDirectory);
            case "validate" -> validate();
            case "train" -> train();
            case "evaluate" -> evaluate();
            case "package" -> pack();
            case "verify" -> verify();
            default -> throw new IllegalArgumentException("unbekannter Schritt " + command);
        }
    }

    Path data(String role) {
        return work.resolve("data").resolve(role + ".conllu");
    }

    Path mco() {
        return work.resolve("model").resolve(recipe.mcoFileName());
    }

    Path distribution() {
        return work.resolve("dist");
    }

    void validate() {
        TreebankAcquisition.verify(recipe.treebank(), treebankDirectory);
        Map<String, TreebankValidation.Report> reports = new LinkedHashMap<>();
        for (String role : List.of("train", "dev", "test")) {
            List<ConlluSentence> sentences = ConlluSentence.read(
                    treebankDirectory.resolve(recipe.treebank().file(role).name()));
            TreebankValidation.Report report = TreebankValidation.validate(sentences);
            log(role + ": " + report.sentences() + " Sätze, " + report.words() + " Wörter, " + report.multiwordTokens()
                    + " Mehrwort-Zeilen und " + report.emptyNodes() + " leere Knoten entfernt, "
                    + report.nonProjectiveSentences() + " nicht projektive Sätze");
            if (!report.valid()) {
                throw new IllegalStateException(role + ": " + report.errors().size() + " Fehler, zuerst "
                        + report.errors().subList(0, Math.min(10, report.errors().size())));
            }
            reports.put(role, report);
            ConlluSentence.write(data(role), sentences.stream().map(InputContract::forTraining).toList());
        }
        writeJson(work.resolve("validation.json"), reports);
    }

    void train() {
        if (!Files.isRegularFile(data("train"))) {
            validate();
        }
        List<ConlluSentence> train = ConlluSentence.read(data("train"));
        TreebankValidation.Report report = TreebankValidation.validate(train);
        log("Training mit " + recipe.training().algorithm() + "/" + recipe.training().learner() + ", Heap " + heap
                + ", höchstens " + recipe.training().timeoutMinutes() + " min");
        MaltModelTrainer.Result result = new MaltModelTrainer(TrainingCli::log)
                .train(recipe, data("train"), mco().getParent(), heap);
        TrainingRun run = new TrainingRun(Hashing.sha256(result.mco()),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), result.duration().toSeconds(), heap,
                result.peakRssMiB().orElse(null), Runtime.version().toString(), report.sentences(), report.words(),
                List.copyOf(report.labels().keySet()));
        writeJson(work.resolve("training.json"), run);
        log("Training " + run.durationSeconds() + " s, Spitzenspeicher " + run.peakRssMiB() + " MiB, SHA-256 "
                + run.mcoSha256());
    }

    void evaluate() {
        TrainingRun run = readTrainingRun();
        requireMcoUnchanged(run);
        ModelEvaluator.Splits splits = new ModelEvaluator.Splits(ConlluSentence.read(data("train")),
                ConlluSentence.read(data("dev")), ConlluSentence.read(data("test")), referenceSentences());
        ModelEvaluator.Report report = new ModelEvaluator(TrainingCli::log).evaluate(MaltModel.load(mco()),
                OpenNlpPosTagger.load(), splits, recipe.minimumMetrics());
        writeJson(work.resolve("evaluation.json"), report);
        report.headline().forEach((key, value) -> log(key + " = " + value));
        log("Prüfsätze: " + report.referenceGoldUpos().passed() + "/" + report.referenceGoldUpos().checks()
                + " Kanten mit Gold-Wortarten, " + report.referencePredictedUpos().passed() + "/"
                + report.referencePredictedUpos().checks() + " mit OpenNLP-Wortarten");
        if (!report.qualityGate().passed()) {
            throw new IllegalStateException("Qualitätsgrenze verfehlt: " + report.qualityGate().failures());
        }
    }

    void pack() {
        TrainingRun run = readTrainingRun();
        requireMcoUnchanged(run);
        ModelEvaluator.Report evaluation = read(work.resolve("evaluation.json"), ModelEvaluator.Report.class);
        if (evaluation.qualityGate() == null || !evaluation.qualityGate().passed()) {
            throw new IllegalStateException("evaluation.json fehlt eine bestandene Qualitätsgrenze; zuerst evaluate");
        }
        ModelManifest manifest = manifest(run, evaluation);
        Map<String, Path> files = new LinkedHashMap<>();
        files.put(recipe.mcoFileName(), mco());
        files.put("evaluation.json", work.resolve("evaluation.json"));
        Path texts = work.resolve("license");
        files.put("LICENSE-MODEL.md", render(recipe.directory().resolve("LICENSE-MODEL.md"), texts));
        files.put("ATTRIBUTION.md", render(recipe.directory().resolve("ATTRIBUTION.md"), texts));
        files.put("LICENSE-" + recipe.treebank().name() + ".txt",
                treebankDirectory.resolve(recipe.treebank().file("license").name()));
        ModelPackager.Assets assets = new ModelPackager().pack(recipe, JSON.toJson(manifest) + "\n", files,
                distribution());
        assets.sha256().forEach((name, sha) -> log(sha + "  " + name));
    }

    void verify() {
        Path zip = distribution().resolve(recipe.releaseTag() + ".zip");
        String expected = checksums().get(zip.getFileName().toString());
        if (expected == null) {
            throw new IllegalStateException("SHA256SUMS nennt " + zip.getFileName() + " nicht; zuerst package");
        }
        ModelConsumerCheck.Result result = ModelConsumerCheck.check(zip, expected, work.resolve("consumer"),
                referenceSentences().get(0));
        log("Verbraucherprüfung bestanden: " + result.manifest().releaseTag() + ", " + result.manifest().mcoFile()
                + " geladen, Prüfsatz geparst:");
        log(result.parsed().format().strip());
    }

    ModelManifest manifest(TrainingRun run, ModelEvaluator.Report evaluation) {
        ModelRecipe.Training training = recipe.training();
        return new ModelManifest(ModelManifest.SCHEMA, recipe.modelId(), recipe.modelVersion(), recipe.releaseTag(),
                recipe.configName(), recipe.mcoFileName(), run.mcoSha256(), recipe.maltParserVersion(),
                new ModelManifest.Training(training.algorithm(), training.learner(), training.featureModel(),
                        Hashing.sha256(recipe.featureModel()),
                        Hashing.sha256(recipe.directory().resolve(recipe.modelId() + ".properties")),
                        System.getProperty("training.commit", "unknown"), run.javaVersion(),
                        System.getProperty("training.gradleVersion", "unknown"), run.trainedAt(),
                        run.durationSeconds(), run.heap(), run.peakRssMiB(), run.sentences(), run.words()),
                new ModelManifest.Treebank(recipe.treebank().name(), recipe.treebank().repository(),
                        recipe.treebank().release(), recipe.treebank().commit(), recipe.treebank().files()),
                ModelManifest.InputSchema.current(), run.labels(), evaluation.headline(), "evaluation.json",
                new ModelManifest.License(recipe.license().model(),
                        "CC-BY-SA-4.0 (" + recipe.treebank().name() + " " + recipe.treebank().release() + ")",
                        "keine Rechte von Google beansprucht, siehe README der Treebank und docs/NLP_MODEL_LICENSE.md",
                        "BSD-3-Clause (MaltParser " + recipe.maltParserVersion() + ")",
                        "MIT (space-harvester)", recipe.license().approval(),
                        List.of("LICENSE-MODEL.md", "ATTRIBUTION.md", "LICENSE-" + recipe.treebank().name() + ".txt")));
    }

    /** Setzt {@code {{releaseTag}}}, {@code {{treebankRelease}}}, {@code {{treebankCommit}}}, {@code {{licenseApproval}}} ein. */
    private Path render(Path template, Path directory) {
        try {
            String text = Files.readString(template, StandardCharsets.UTF_8)
                    .replace("{{releaseTag}}", recipe.releaseTag())
                    .replace("{{treebankRelease}}", recipe.treebank().release())
                    .replace("{{treebankCommit}}", recipe.treebank().commit())
                    .replace("{{licenseModel}}", recipe.license().model())
                    .replace("{{licenseApproval}}", recipe.license().approval());
            Files.createDirectories(directory);
            Path target = directory.resolve(template.getFileName());
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<ConlluSentence> referenceSentences() {
        return ConlluSentence.read(recipe.directory().resolve("german-reference-sentences.conllu"));
    }

    private Map<String, String> checksums() {
        Map<String, String> sums = new HashMap<>();
        try {
            for (String line : Files.readAllLines(distribution().resolve("SHA256SUMS"), StandardCharsets.UTF_8)) {
                String[] parts = line.split(" {2}", 2);
                if (parts.length == 2) {
                    sums.put(parts[1].trim(), parts[0].trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sums;
    }

    private TrainingRun readTrainingRun() {
        Path file = work.resolve("training.json");
        if (!Files.isRegularFile(file) || !Files.isRegularFile(mco())) {
            throw new IllegalStateException("kein trainiertes Modell in " + work + "; zuerst trainGermanModel");
        }
        return read(file, TrainingRun.class);
    }

    private void requireMcoUnchanged(TrainingRun run) {
        String actual = Hashing.sha256(mco());
        if (!actual.equals(run.mcoSha256())) {
            throw new IllegalStateException(mco() + " wurde nach dem Training verändert (" + actual + ")");
        }
    }

    static <T> T read(Path file, Class<T> type) {
        try {
            return JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void log(String message) {
        System.out.println(message);
    }
}
