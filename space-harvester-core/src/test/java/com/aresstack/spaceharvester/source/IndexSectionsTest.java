package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.TextSection;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.testcorpus.Archives;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Abschnitte für den Volltextindex (ADR 0026, AP4b): einzeln aus der Ablage gelesen, auch über Teile hinweg, mit
 * einmal gelesenem Manifest; nie alle Abschnitte eines Behälters zugleich im Speicher.
 */
class IndexSectionsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @TempDir
    Path directory;

    @Test
    void sectionsOfEnclosedFilesAreReadOneAtATime() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int file = 1; file <= 3; file++) {
            StringBuilder text = new StringBuilder();
            for (int line = 0; text.length() < 250_000; line++) {
                text.append("Datei ").append(file).append(" Zeile ").append(line).append(" Fülltext\n\n");
            }
            entries.put("teil" + file + ".txt", text.toString().getBytes(StandardCharsets.UTF_8));
        }
        byte[] zip = Archives.zip(entries);
        FileSystemAssetStore store = new FileSystemAssetStore(directory.resolve("assets"));
        Counting extractionStore = new Counting(directory.resolve("extractions"));
        DocumentExtractions extractions = new DocumentExtractions(new TikaDocumentStructure(), extractionStore,
                ExtractionLimits.DEFAULTS);
        Asset asset = new Asset("wiki", "1", "a1", "1", "teile.zip", Archives.ZIP, AssetKind.OTHER, zip.length,
                Assets.sha256(zip), NOW);
        store.put(asset, zip);
        extractions.text(asset, store, NOW);
        extractionStore.read.set(0);

        List<DocumentExtractions.IndexSection> all = new ArrayList<>();
        try (Stream<DocumentExtractions.IndexSection> sections = extractions.indexSections(asset).orElseThrow()) {
            Iterator<DocumentExtractions.IndexSection> iterator = sections.iterator();
            assertThat(iterator.hasNext()).isTrue();
            all.add(iterator.next());
            assertThat(extractionStore.read).as("nur der erste Abschnitt gelesen").hasValue(1);
            iterator.forEachRemaining(all::add);
        }

        assertThat(extractionStore.manifests).as("Manifest einmal").hasValue(1);
        assertThat(all).extracting(DocumentExtractions.IndexSection::partTitle).contains("teil1.txt", "teil2.txt",
                "teil3.txt");
        assertThat(all.stream().map(DocumentExtractions.IndexSection::partId).distinct()).hasSize(3);
        assertThat(all).hasSizeGreaterThan(3);
        assertThat(Set.copyOf(all.stream().map(DocumentExtractions.IndexSection::section).toList())).contains(0, 1);
    }

    /** Zählt gelesene Abschnitte der Teile und Manifest-Zugriffe für Teile. */
    private static final class Counting extends FileSystemExtractionStore {
        private final AtomicInteger read = new AtomicInteger();
        private final AtomicInteger manifests = new AtomicInteger();

        Counting(Path root) {
            super(root);
        }

        @Override
        public Optional<PartSections> partSections(ExtractionKey key) {
            manifests.incrementAndGet();
            return super.partSections(key).map(parts -> new PartSections() {
                @Override
                public Set<String> keys() {
                    return parts.keys();
                }

                @Override
                public Stream<TextSection> sections(String partKey) {
                    return parts.sections(partKey).peek(section -> read.incrementAndGet());
                }
            });
        }

        @Override
        public Stream<TextSection> partSections(ExtractionKey key, String partKey) {
            manifests.incrementAndGet();
            return super.partSections(key, partKey);
        }
    }
}
