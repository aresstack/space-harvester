package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adressauflösung über alle Quellen (#178, wie {@code ResolveKnowledgeAddressUseCase} im Enterprise AI Client): jede
 * Quelle, deren Adressmuster passt, wird einzeln über {@link PageQueries#pageByUrl} gefragt; geraten wird nie
 * zwischen Quellen. Ein Kandidat erscheint nur, wenn der Aufrufer die Seite lesen darf; eine nicht erreichbare Quelle
 * verdeckt die anderen nicht und steht in {@link Resolution#unavailableSources()}.
 */
public final class PageAddresses {

    private final SourceCatalog catalog;
    private final AccessGuard access;
    private final PageQueries pages;

    public PageAddresses(SourceCatalog catalog, AccessGuard access, PageQueries pages) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.access = Objects.requireNonNull(access, "access");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /**
     * @param address URL, Lesezeichen {@code space-harvester://…} oder Ressourcen-ID einer Quelle
     * @throws InvalidQueryException wenn die Adresse fehlt, zu lang ist oder ein fehlerhaftes Lesezeichen ist
     */
    public Resolution resolve(AccessContext caller, String address) {
        if (address == null || address.isBlank() || address.length() > PageUrls.MAX_URL_LENGTH) {
            throw new InvalidQueryException("address fehlt oder ist länger als " + PageUrls.MAX_URL_LENGTH
                    + " Zeichen");
        }
        String value = address.trim();
        Optional<PageBookmark> bookmark;
        try {
            bookmark = PageBookmark.parse(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException(e.getMessage());
        }
        List<HarvestSource> candidates = bookmark.isPresent()
                ? catalog.find(bookmark.get().sourceId()).stream().toList()
                : catalog.all();
        List<Match> matches = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (HarvestSource source : candidates) {
            if (!access.permits(caller, ResourceScope.source(source.id()), AccessKind.CONTENT)) {
                continue;
            }
            try {
                DeliveredPage page = pages.pageByUrl(caller, source.id(), value, false);
                matches.add(Match.of(page.header()));
            } catch (CacheMissException | InvalidQueryException | AccessDeniedException
                     | UnknownSourceException e) {
                // Keine (sichtbare) Seite dieser Quelle.
            } catch (SourceAccessException e) {
                if (e.reason() == SourceAccessException.Reason.UNAVAILABLE
                        || e.reason() == SourceAccessException.Reason.RATE_LIMITED) {
                    unavailable.add(source.id());
                }
            }
        }
        return new Resolution(matches, unavailable);
    }

    /**
     * @param matches            die Seiten, auf die die Adresse zeigt, je Quelle höchstens eine, in Quellenreihenfolge
     * @param unavailableSources Quellen, deren Muster passt, die aber für die Auflösung nicht erreichbar waren
     */
    public record Resolution(List<Match> matches, List<String> unavailableSources) {

        public Resolution {
            matches = List.copyOf(matches);
            unavailableSources = List.copyOf(unavailableSources);
        }
    }

    /**
     * Eine aufgelöste Seite.
     *
     * @param bookmark die stabile Ressourcen-ID mit der aktuellen Version
     */
    public record Match(String sourceId, String pageId, String spaceKey, String title, String version, URI url,
                        PageBookmark bookmark) {

        static Match of(PageHeader header) {
            return new Match(header.key().sourceId(), header.key().pageId(), header.metadata().partitionKey(),
                    header.metadata().title(), header.metadata().version(), header.metadata().url(),
                    PageBookmark.of(header));
        }
    }
}
