package com.aresstack.writing4j.book;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * Architecture: how the research steered its search (a concept, a mind map, a search plan) is no input of the writing.
 * Its input is mission, Weidezaun, basket and writing settings; the chapters come from the material. No source file of
 * Writing4j names a card, the concept or a share of a card.
 */
public class WritingWithoutConceptArchitectureTest {

    private static final File ROOT = new File("src/main/java/com/aresstack/writing4j");
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(Concept\\w*|ofCard|KONZEPT|KARTE|getCards"
            + "|cardAnchor\\w*|MissionQuestionDrafter)\\b");

    @Test
    public void theWritingKnowsNoConcept() throws IOException {
        List<String> found = new ArrayList<String>();
        scan(ROOT, found);
        assertTrue("the writing depends on the concept:\n" + String.join("\n", found), found.isEmpty());
    }

    private static void scan(File file, List<String> found) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            assertTrue("cannot list " + file, children != null);
            for (File child : children) {
                scan(child, found);
            }
            return;
        }
        if (!file.getName().endsWith(".java")) {
            return;
        }
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = FORBIDDEN.matcher(lines.get(i));
            if (matcher.find()) {
                found.add(file.getPath() + ":" + (i + 1) + ": " + lines.get(i).trim());
            }
        }
    }
}
