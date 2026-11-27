package com.aresstack.spaceharvester.app.research;

import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookArtifactsTest {

    @TempDir
    Path data;

    private BookArtifacts books(List<BookEditions.Source> members, String version) {
        return new BookArtifacts(() -> data, id -> data.resolve("projekte").resolve(id).resolve("artefakte"),
                folderId -> members, source -> version);
    }

    @Test
    void existingEditionsBecomeVersionsOfOneBookArtifactInOrderWithoutLosingAny() throws Exception {
        Instant at = Instant.parse("2026-11-27T10:00:00Z");
        BookEditions.add(data, "ordner-1", at, "# Erste\n\nText eins.", "Bericht", List.of());
        BookEditions.add(data, "ordner-1", at.plusSeconds(60), "# Zweite\n\nText zwei.", "Bericht", List.of());
        BookArtifacts books = books(List.of(), null);

        ChatArtifact book = books.publish("ordner-1", null, "Erfundener Ordner");

        assertThat(book.id()).startsWith(ChatArtifactStore.BOOK);
        assertThat(book.title()).isEqualTo("Erfundener Ordner");
        assertThat(book.editions()).hasSize(2);
        assertThat(book.content()).contains("Text zwei.");
        assertThat(book.edition(1).document().markdown()).contains("Text eins.");
        assertThat(books.artifactOf("ordner-1", null)).isEqualTo(book.id());
        assertThat(books.folderOf(null, book.id())).isEqualTo("ordner-1");
        assertThat(books.folderOf("p1", book.id())).as("je Projekt ein eigenes Buch").isNull();

        // ein zweites Mal ändert nichts, eine neue Auflage kommt als weitere Version dazu
        assertThat(books.publish("ordner-1", null, "Erfundener Ordner").editions()).hasSize(2);
        BookEditions.add(data, "ordner-1", at.plusSeconds(120), "# Dritte\n\nText drei.", "Bericht", List.of());
        ChatArtifact third = books.publish("ordner-1", null, "Erfundener Ordner");
        assertThat(third.id()).isEqualTo(book.id());
        assertThat(third.editions()).hasSize(3);
        assertThat(third.content()).contains("Text drei.");
    }

    @Test
    void eachProjectHasItsOwnBookOfTheFolder() throws Exception {
        BookEditions.add(data, "ordner-2", Instant.parse("2026-11-27T10:00:00Z"), "# Buch", "", List.of());
        BookArtifacts books = books(List.of(), null);

        ChatArtifact standard = books.publish("ordner-2", null, "Ordner");
        ChatArtifact other = books.publish("ordner-2", "p1", "Ordner");

        assertThat(books.artifactOf("ordner-2", "p1")).isEqualTo(other.id());
        assertThat(books.artifactOf("ordner-2", null)).isEqualTo(standard.id());
        assertThat(books.folderOf("p1", other.id())).isEqualTo("ordner-2");
        assertThat(books.publish("ordner-ohne-buch", null, "Leer")).isNull();
    }

    @Test
    void staleFollowsTheFolderPagesAndTheirVersions() throws Exception {
        BookEditions.Source page = new BookEditions.Source("wiki", "1", null);
        BookEditions.add(data, "ordner-3", Instant.parse("2026-11-27T10:00:00Z"), "# Buch", "",
                List.of(new BookEditions.Source("wiki", "1", "v1")), List.of(page));

        assertThat(books(List.of(page), "v1").stale("ordner-3")).isFalse();
        assertThat(books(List.of(page), "v2").stale("ordner-3")).isTrue();
        assertThat(books(List.of(), "v1").stale("ordner-3")).isTrue();
        assertThat(books(List.of(), "v1").stale("ordner-ohne-buch")).isFalse();
    }
}
