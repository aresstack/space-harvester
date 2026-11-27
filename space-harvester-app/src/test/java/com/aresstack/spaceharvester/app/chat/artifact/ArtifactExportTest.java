package com.aresstack.spaceharvester.app.chat.artifact;

import com.aresstack.spaceharvester.app.chat.ChatExport;
import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Export nach Markdown, Word, PDF und SVG, benannt nach dem Titel, nie überschreibend (ADR 0045, Abschnitt 8). */
class ArtifactExportTest {

    @TempDir
    Path home;

    @Test
    void documentsAreExportedInAllFormatsNamedByTitleWithoutOverwriting() throws Exception {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("x", 1), 1);
        chats.save(node, chat);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        ChatArtifact document = store.createDocument(chats.folder(node), chat.id(), "Garten: Fibel",
                "# Garten: Fibel\n\nBohnen.\n\n## Boden\n\nHumus.", ChatArtifact.Origin.AI);
        ChatArtifact graphic = store.createGraphic(chats.folder(node), chat.id(), "Beet",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>", ChatArtifact.Origin.AI);
        Path out = home.resolve("ausgabe");

        Path markdown = ArtifactExport.write(document, ChatExport.Format.MARKDOWN, out);
        Path again = ArtifactExport.write(document, ChatExport.Format.MARKDOWN, out);
        Path word = ArtifactExport.write(document, ChatExport.Format.WORD, out);
        Path pdf = ArtifactExport.write(document, ChatExport.Format.PDF, out);
        Path svg = ArtifactExport.writeSvg(graphic, out);

        assertThat(markdown.getFileName().toString()).isEqualTo("Garten Fibel.md");
        assertThat(again.getFileName().toString()).isEqualTo("Garten Fibel (2).md");
        assertThat(Files.readString(markdown)).isEqualTo(document.content());
        assertThat(Files.size(word)).isPositive();
        assertThat(Files.size(pdf)).isPositive();
        assertThat(Files.readString(svg)).contains("<svg");
        assertThat(ArtifactExport.document(document).text())
                .as("die Titelüberschrift steht nicht doppelt").startsWith("Bohnen.").contains("## Boden");

        ChatArtifact code = store.createDocument(chats.folder(node), chat.id(), "Code",
                "# Code\n\n    int x = 1;", ChatArtifact.Origin.AI);
        assertThat(ArtifactExport.document(code).text()).as("Codeblock bleibt eingerückt").startsWith("    int x");
    }

    @Test
    void parallelExportsWithTheSameTitleGetOwnFiles() throws Exception {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("x", 1), 1);
        chats.save(node, chat);
        ChatArtifact document = new ChatArtifactStore(chats).createDocument(chats.folder(node), chat.id(), "Brief",
                "Hallo.", ChatArtifact.Origin.AI);
        Path out = home.resolve("parallel");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.List<java.util.concurrent.Future<Path>> files = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                files.add(pool.submit(() -> ArtifactExport.write(document, ChatExport.Format.MARKDOWN, out)));
            }
            java.util.Set<Path> distinct = new java.util.HashSet<>();
            for (java.util.concurrent.Future<Path> file : files) {
                distinct.add(file.get());
            }
            assertThat(distinct).hasSize(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void embeddedGraphicsGoIntoEveryFormat() throws Exception {
        ChatStore chats = new ChatStore(() -> home.resolve("chats"));
        ChatNode node = new ChatNode("wiki", null, null);
        ChatRecord chat = ChatRecord.fresh(1).plus(ChatRecord.Message.user("x", 1), 1);
        chats.save(node, chat);
        ChatArtifactStore store = new ChatArtifactStore(chats);
        Path folder = chats.folder(node);
        store.createGraphic(folder, chat.id(), "Beet", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"40\" "
                + "height=\"20\"><rect width=\"40\" height=\"20\" fill=\"#8c8\"/></svg>", ChatArtifact.Origin.AI);
        ChatArtifact document = store.createDocument(folder, chat.id(), "Plan",
                "## Beet\n\nDrei Reihen.\n\n![Beet](artefakt:G1)", ChatArtifact.Origin.AI);
        Path out = home.resolve("ausgabe");

        Path markdown = ArtifactExport.write(document, ChatExport.Format.MARKDOWN, out,
                id -> store.read(folder, chat.id(), id));
        Path word = ArtifactExport.write(document, ChatExport.Format.WORD, out, id -> store.read(folder, chat.id(), id));

        assertThat(Files.readString(markdown)).contains("````svg").contains("<rect").doesNotContain("artefakt:");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(word.toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName)).anyMatch(name -> name.startsWith("word/media/"));
        }
    }
}
