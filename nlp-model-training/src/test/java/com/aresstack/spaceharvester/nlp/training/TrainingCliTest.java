package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der ganze Ablauf validate → train → evaluate → package → verify mit einem winzigen Modell aus den eigenen
 * Prüfsätzen statt der GSD-Treebank: kein Netz, wenige Sekunden. Prüft die Werkzeuge, nicht die Modellqualität.
 */
class TrainingCliTest {

    @Test
    void runsTheWholePipelineOnTinyData(@TempDir Path root) throws IOException {
        Path model = root.resolve("model");
        Path treebank = root.resolve("treebank");
        Path work = root.resolve("work");
        Files.createDirectories(model);
        Files.createDirectories(treebank);
        for (String file : List.of("feature-model.xml", "german-reference-sentences.conllu", "ATTRIBUTION.md",
                "LICENSE-MODEL.md")) {
            Files.copy(Path.of("model", file), model.resolve(file));
        }
        Path sentences = Path.of("model/german-reference-sentences.conllu");
        for (String role : List.of("train", "dev", "test")) {
            Files.copy(sentences, treebank.resolve("tiny-" + role + ".conllu"));
        }
        Files.writeString(treebank.resolve("LICENSE.txt"), "Testlizenz\n");
        Files.writeString(treebank.resolve("README.md"), "Test\n");

        List<String> recipe = new ArrayList<>();
        for (String line : Files.readAllLines(ModelRecipeTest.RECIPE, StandardCharsets.UTF_8)) {
            if (line.startsWith("treebank.file.")) {
                continue;
            }
            recipe.add(line.startsWith("training.heap=") ? "training.heap=512m" : line);
        }
        for (String role : List.of("train", "dev", "test", "license", "readme")) {
            String name = switch (role) {
                case "license" -> "LICENSE.txt";
                case "readme" -> "README.md";
                default -> "tiny-" + role + ".conllu";
            };
            recipe.add("treebank.file." + role + "=" + name);
            recipe.add("treebank.file." + role + ".sha256=" + Hashing.sha256(treebank.resolve(name)));
        }
        Path recipeFile = Files.write(model.resolve("maltparser-de-gsd.properties"), recipe, StandardCharsets.UTF_8);

        TrainingCli cli = new TrainingCli(ModelRecipe.load(recipeFile), treebank, work, null);
        for (String step : List.of("validate", "train", "evaluate", "package", "verify")) {
            cli.run(step);
        }

        ModelRecipe loaded = ModelRecipe.load(recipeFile);
        Path zip = work.resolve("dist").resolve(loaded.releaseTag() + ".zip");
        assertEquals(List.of("ATTRIBUTION.md", "LICENSE-MODEL.md", "LICENSE-UD_German-GSD.txt", "evaluation.json",
                "maltparser-de-gsd.mco", "model-manifest.json"), entries(zip));
        ModelManifest manifest = TrainingCli.read(work.resolve("dist").resolve(loaded.releaseTag() + ".manifest.json"),
                ModelManifest.class);
        assertEquals(ModelManifest.SCHEMA, manifest.schemaVersion());
        assertEquals(List.of("FORM", "UPOS"), manifest.inputSchema().columns());
        assertEquals(Hashing.sha256(work.resolve("model/maltparser-de-gsd.mco")), manifest.mcoSha256());
        assertTrue(manifest.labels().contains("obl:arg"));
        assertEquals("pending", manifest.license().releaseApproval());
        assertTrue(manifest.metrics().containsKey("test.predicted.las"));
        String licence = read(zip, "LICENSE-MODEL.md");
        assertTrue(licence.contains(loaded.releaseTag()) && !licence.contains("{{"), licence);
    }

    @Test
    void packagingRefusesWithoutTraining(@TempDir Path work) {
        TrainingCli cli = new TrainingCli(ModelRecipe.load(ModelRecipeTest.RECIPE), work, work, null);
        assertThrows(IllegalStateException.class, () -> cli.run("package"));
        assertThrows(IllegalStateException.class, () -> cli.run("evaluate"));
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zipIn = new ZipInputStream(in)) {
            for (var entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String read(Path zip, String name) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zipIn = new ZipInputStream(in)) {
            for (var entry = zipIn.getNextEntry(); entry != null; entry = zipIn.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    return new String(zipIn.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError(name + " fehlt");
    }
}
