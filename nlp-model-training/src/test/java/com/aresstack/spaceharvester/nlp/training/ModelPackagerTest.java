package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelPackagerTest {

    @Test
    void writesReproducibleAssetsWithChecksums(@TempDir Path directory) throws IOException {
        ModelRecipe recipe = ModelRecipe.load(ModelRecipeTest.RECIPE);
        Path mco = Files.writeString(directory.resolve("x.mco"), "modell");
        ModelPackager.Assets first = new ModelPackager().pack(recipe, "{}\n",
                Map.of(recipe.mcoFileName(), mco), directory.resolve("a"));
        ModelPackager.Assets second = new ModelPackager().pack(recipe, "{}\n",
                Map.of(recipe.mcoFileName(), mco), directory.resolve("b"));
        assertEquals(first.sha256(), second.sha256(), "gleicher Inhalt, gleiche SHA-256");
        String tag = recipe.releaseTag();
        assertEquals(tag + ".zip", first.zip().getFileName().toString());
        String sums = Files.readString(first.checksums());
        assertTrue(sums.matches("([0-9a-f]{64}  \\S+\\n){2}"), sums);
        assertTrue(sums.contains(Hashing.sha256(first.zip()) + "  " + tag + ".zip\n"));
        assertTrue(sums.contains(Hashing.sha256(first.manifest()) + "  " + tag + ".manifest.json\n"));
    }
}
