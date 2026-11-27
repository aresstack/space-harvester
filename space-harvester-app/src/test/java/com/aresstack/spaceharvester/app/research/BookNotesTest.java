package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.scope.Fence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Notizen am Ordner behalten Stichpunkte, Fragen und die Länge, wie der Leser sie eingab (Zahl und Einheit). */
class BookNotesTest {

    @Test
    void lengthAsTypedIdeasAndQuestionsSurviveSaveAndLoad(@TempDir Path data) throws Exception {
        BookNotes notes = BookNotes.of(Fence.of("Streuobstwiesen pflegen"), 4000)
                .withIdeas("- Schnitt\n- Mahd", List.of("Wann schneiden?"))
                .withLength(10, "PAGES");
        notes.save(data, "ordner-1");

        BookNotes loaded = BookNotes.load(data, "ordner-1").orElseThrow();
        assertThat(loaded.targetWords()).isEqualTo(4000);
        assertThat(loaded.lengthAmount()).isEqualTo(10);
        assertThat(loaded.lengthUnit()).isEqualTo("PAGES");
        assertThat(loaded.ideas()).isEqualTo("- Schnitt\n- Mahd");
        assertThat(loaded.questions()).containsExactly("Wann schneiden?");
        assertThat(loaded.withFence(Fence.of("anders"), 1).lengthUnit()).as("ein neuer Zaun behält die Einheit")
                .isEqualTo("PAGES");
    }

    @Test
    void notesWrittenBeforeTheLengthUnitStillLoad(@TempDir Path data) throws Exception {
        Path file = BookNotes.file(data, "alt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"mission\":\"M\",\"targetWords\":1500}", StandardCharsets.UTF_8);

        BookNotes loaded = BookNotes.load(data, "alt").orElseThrow();
        assertThat(loaded.targetWords()).isEqualTo(1500);
        assertThat(loaded.lengthAmount()).isZero();
        assertThat(loaded.lengthUnit()).isEmpty();
    }
}
