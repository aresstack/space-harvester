package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRecipeTest {

    static final Path RECIPE = Path.of("model/maltparser-de-gsd.properties");

    @Test
    void loadsTheCommittedRecipe() {
        ModelRecipe recipe = ModelRecipe.load(RECIPE);
        assertEquals("maltparser-de-gsd-v" + recipe.modelVersion(), recipe.releaseTag());
        assertEquals("maltparser-de-gsd.mco", recipe.mcoFileName());
        assertEquals(5, recipe.treebank().files().size());
        assertTrue(recipe.treebank().downloadUrl(recipe.treebank().file("train")).startsWith(
                "https://raw.githubusercontent.com/UniversalDependencies/UD_German-GSD/" + recipe.treebank().commit()));
    }

    @Test
    void featureModelReadsOnlyFormAndUpos() throws IOException {
        String xml = Files.readString(ModelRecipe.load(RECIPE).featureModel());
        assertEquals(java.util.Set.copyOf(InputContract.COLUMNS), java.util.Set.copyOf(ModelRecipe.inputColumns(xml)));
    }

    @Test
    void rejectsFeatureModelOutsideTheContract(@TempDir Path directory) throws IOException {
        Files.copy(RECIPE, directory.resolve("maltparser-de-gsd.properties"));
        Files.writeString(directory.resolve("feature-model.xml"),
                "<featuremodels><featuremodel name=\"x\"><feature>InputColumn(FEATS, Stack[0])</feature>"
                        + "</featuremodel></featuremodels>");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ModelRecipe.load(directory.resolve("maltparser-de-gsd.properties")));
        assertTrue(error.getMessage().contains("FEATS"), error.getMessage());
    }

    @Test
    void maltParserVersionMatchesTheVersionCatalog() throws IOException {
        Matcher matcher = Pattern.compile("(?m)^maltparser = \"([^\"]+)\"")
                .matcher(Files.readString(Path.of("../gradle/libs.versions.toml")));
        assertTrue(matcher.find());
        assertEquals(matcher.group(1), ModelRecipe.load(RECIPE).maltParserVersion());
    }

    @Test
    void neitherTheServiceNorOtherProjectsDependOnTraining() throws IOException {
        assertTrue(!Files.readString(Path.of("../build.gradle")).contains("nlp-model-training"));
        try (var files = Files.walk(Path.of(".."), 2)) {
            files.filter(file -> file.getFileName().toString().equals("build.gradle"))
                    .filter(file -> !file.toAbsolutePath().normalize().startsWith(Path.of("").toAbsolutePath()))
                    .forEach(file -> {
                        try {
                            assertTrue(!Files.readString(file).contains("nlp-model-training"), file.toString());
                        } catch (IOException e) {
                            throw new AssertionError(e);
                        }
                    });
        }
    }
}
