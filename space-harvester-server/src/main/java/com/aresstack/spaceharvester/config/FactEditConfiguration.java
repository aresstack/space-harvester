package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.facts.EditedFactStore;
import com.aresstack.spaceharvester.application.facts.FactEditStore;
import com.aresstack.spaceharvester.application.facts.FactEdits;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.persistence.files.FileFactEditStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manuelle und geprüfte Fakten (#182): Prüfablage als Dateien außerhalb des Caches
 * ({@code space-harvester.facts.directory}, Standard {@code data/facts}, wirksam nach Neustart). Die Payloads stammen
 * aus dem Inhalt der Seiten; das Verzeichnis gehört nur dem Dienstkonto.
 */
@Configuration(proxyBeanMethods = false)
class FactEditConfiguration {

    /** Ohne Ablage der Anreicherungen: keine automatischen Fakten. */
    static final FactStore NO_FACTS = new FactStore() {
        @Override
        public List<MetadataFact> factsOf(PageKey page) {
            return List.of();
        }

        @Override
        public List<MetadataFact> facts(Query query, String after, int limit) {
            return List.of();
        }
    };

    @Bean
    FactEditStore factEditStore(@Value("${space-harvester.facts.directory:data/facts}") Path directory) {
        return new FileFactEditStore(directory);
    }

    @Bean
    FactEdits factEdits(AccessGuard access, PageQueries pages, ObjectProvider<Enrichments> enrichments,
                        ObjectProvider<FactStore> store, FactEditStore edits) {
        Enrichments available = enrichments.getIfAvailable();
        return new FactEdits(access, pages, available, facts(available, store, edits), edits, Clock.systemUTC());
    }

    /**
     * Die Fakten aus derselben Ablage wie die Anreicherungen (Lucene-Cache oder Speicher) mit der Prüfablage. Kein
     * eigener Bean, damit {@link FactStore} eindeutig die Ablage der Anreicherungen bleibt.
     */
    static EditedFactStore facts(Enrichments enrichments, ObjectProvider<FactStore> store, FactEditStore edits) {
        FactStore base = (enrichments == null ? Optional.<FactStore>empty() : enrichments.facts())
                .orElseGet(() -> store.getIfUnique(() -> NO_FACTS));
        return new EditedFactStore(base, edits);
    }
}
