package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.AssetText;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.testcorpus.Archives;
import com.aresstack.spaceharvester.testcorpus.Corpus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Suchtest über den Korpus (ADR 0026, Abnahme 1; Architekt 13.11.2026 zu AP7): Der Text, den Volltextindex und Export
 * bis AP4 je Anhang bekommen ({@link DocumentExtractions#text}), enthält jede Marke, die der bisherige Weg
 * ({@link TikaDocumentText}) fand. Enthaltene Dateien sind eigene Einträge, ihr Text bleibt bis AP4 über den Behälter
 * auffindbar.
 */
class CorpusSearchParityTest {

    private static final Pattern MARK = Pattern.compile("MARKE-[A-Z0-9][A-Z0-9-]*[A-Z0-9]");
    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");

    @TempDir
    Path directory;

    @Test
    void theNewTextFindsEveryMarkTheOldTextFound() {
        Map<String, byte[]> mails = new LinkedHashMap<>();
        mails.put("doppelt.eml", Corpus.duplicateNames(false).bytes());
        mails.put("zip.eml", Corpus.zipInMail().bytes());
        mails.put("weiter.eml", Corpus.mailInMail().bytes());
        mails.put("outlook.eml", Corpus.outlookMailInMail().bytes());
        mails.put("defekt.eml", Corpus.damaged().bytes());
        mails.put("ebenen.eml", Corpus.nestedMails(4).bytes());
        FileSystemAssetStore store = new FileSystemAssetStore(directory.resolve("assets"));
        DocumentExtractions extractions = new DocumentExtractions(new TikaDocumentStructure(),
                new FileSystemExtractionStore(directory.resolve("extractions")), ExtractionLimits.DEFAULTS);
        TikaDocumentText old = new TikaDocumentText();

        mails.forEach((name, bytes) -> {
            String type = "message/rfc822";
            Set<String> before;
            try {
                before = marks(old.extract(name, type, bytes).text());
            } catch (RuntimeException e) {
                throw new AssertionError(name + " (bisheriger Weg)", e);
            }
            Asset asset = new Asset("wiki", "1", name, "1", name, type, AssetKind.OTHER, bytes.length,
                    Assets.sha256(bytes), NOW);
            store.put(asset, bytes);

            AssetText text;
            try {
                text = extractions.text(asset, store, NOW);
            } catch (RuntimeException e) {
                throw new AssertionError(name + ": " + extractions.stored(asset.sha256()).map(x -> x.document()
                        .outcome() + " chars=" + x.chars()).orElse("nicht abgelegt"), e);
            }

            assertThat(before).as("%s: der bisherige Weg findet Marken", name).isNotEmpty();
            assertThat(marks(text.text())).as(name).containsAll(before);
        });
    }

    @Test
    void anArchiveAsAttachmentGivesTheTextOfItsEntries() {
        byte[] bytes = Archives.nested(3);
        FileSystemAssetStore store = new FileSystemAssetStore(directory.resolve("assets"));
        DocumentExtractions extractions = new DocumentExtractions(new TikaDocumentStructure(),
                new FileSystemExtractionStore(directory.resolve("extractions")), ExtractionLimits.DEFAULTS);
        Asset asset = new Asset("wiki", "1", "archiv", "1", "archiv.zip", Archives.ZIP, AssetKind.OTHER, bytes.length,
                Assets.sha256(bytes), NOW);
        store.put(asset, bytes);

        AssetText text = extractions.text(asset, store, NOW);

        assertThat(text.text()).contains(Archives.levelMark(1), Archives.levelMark(2), Archives.levelMark(3))
                .doesNotContain(".txt", ".zip");
        assertThat(extractions.stored(asset.sha256()).orElseThrow().document().directory()).isNotEmpty();
    }

    private static Set<String> marks(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = MARK.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
