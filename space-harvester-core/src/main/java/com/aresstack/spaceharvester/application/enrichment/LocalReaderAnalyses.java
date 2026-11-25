package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationBuilder;
import com.aresstack.spaceharvester.application.passages.DocumentNavigationView;
import com.aresstack.spaceharvester.application.passages.DocumentPassages;
import com.aresstack.spaceharvester.application.passages.NavigationSection;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassagePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.terms.TermOccurrence;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Adapt stored document analyses to the bounded reader without recalculating or storing page analyses. */
public final class LocalReaderAnalyses {

    private final LocalReadingText reading;
    private final Enrichments enrichments;
    private final DocumentNavigationBuilder navigation;

    public LocalReaderAnalyses(LocalReadingText reading, Enrichments enrichments, DocumentNavigationBuilder navigation) {
        this.reading = Objects.requireNonNull(reading, "reading");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    public Optional<Result> read(PageSnapshot snapshot) {
        return reading.read(snapshot).map(text -> combine(snapshot, text));
    }

    private Result combine(PageSnapshot snapshot, LocalReadingText.Reading reading) {
        String basis = DocumentEnrichments.basis(reading.extraction()) + "|";
        Map<DocumentKey, List<Enrichment>> documents = new HashMap<>();
        for (Enrichment result : enrichments.store().findDocuments(snapshot.key())) {
            if (result.document().localOriginal()) {
                documents.computeIfAbsent(result.document(), key -> new ArrayList<>()).add(result);
            }
        }
        Map<String, String> versions = enrichments.processorVersions();
        Map<DocumentKey, Map<Integer, List<String>>> ids = new HashMap<>();
        List<PagePassages.Passage> passages = new ArrayList<>();
        List<NavigationSection> chapters = new ArrayList<>();
        Map<String, PageAcronyms.Acronym> acronyms = new LinkedHashMap<>();
        Map<String, TermPayload.Entry> terms = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        PageAcronyms firstAcronyms = null;
        PageTerms firstTerms = null;
        PagePassages firstPassages = null;
        DocumentNavigationView firstNavigation = null;
        PagePassages.State acronymState = PagePassages.State.CURRENT;
        PagePassages.State termState = PagePassages.State.CURRENT;
        PagePassages.State passageState = PagePassages.State.CURRENT;
        PagePassages.State navState = PagePassages.State.CURRENT;
        boolean semanticNavigation = false;
        int sentenceOffset = 0;
        for (LocalReadingText.Slice slice : reading.slices()) {
            List<Enrichment> own = documents.getOrDefault(slice.document(), List.of());
            List<Enrichments.View> views = own.stream().filter(result -> result.section() == slice.section())
                    .map(result -> new Enrichments.View(result, freshness(result, slice, versions, basis))).toList();
            PageAcronyms sectionAcronyms = PageAcronyms.read(snapshot.key(), views, slice.text());
            PageTerms sectionTerms = PageTerms.read(snapshot.key(), views, slice.text());
            PagePassages sectionPassages = PagePassages.read(snapshot.key(), views, slice.text());
            DocumentNavigationView sectionNavigation = DocumentNavigationView.read(snapshot.key(), views,
                    slice.text(), navigation);
            if (firstAcronyms == null) {
                firstAcronyms = sectionAcronyms;
                firstTerms = sectionTerms;
                firstPassages = sectionPassages;
                firstNavigation = sectionNavigation;
            }
            acronymState = merge(acronymState, PagePassages.State.valueOf(sectionAcronyms.state().name()));
            termState = merge(termState, PagePassages.State.valueOf(sectionTerms.state().name()));
            passageState = merge(passageState, sectionPassages.state());
            navState = merge(navState, sectionNavigation.state());
            warnings.addAll(sectionAcronyms.warnings());
            warnings.addAll(sectionTerms.warnings());
            warnings.addAll(sectionPassages.warnings());
            for (PageAcronyms.Acronym acronym : sectionAcronyms.acronyms()) {
                var occurrences = acronym.occurrences().stream().filter(o -> slice.contains(o.start(), o.end()))
                        .map(o -> new PageAcronyms.Occurrence(slice.offset() + o.start(), slice.offset() + o.end()))
                        .toList();
                if (!occurrences.isEmpty()) {
                    acronyms.merge(acronym.acronym(), new PageAcronyms.Acronym(acronym.acronym(),
                            occurrences.size(), acronym.expansion(), acronym.section(), occurrences),
                            LocalReaderAnalyses::mergeAcronyms);
                }
            }
            for (TermPayload.Entry term : sectionTerms.terms()) {
                var occurrences = term.occurrences().stream().filter(o -> slice.contains(o.start(), o.end()))
                        .map(o -> new TermOccurrence(slice.offset() + o.start(), slice.offset() + o.end())).toList();
                if (!occurrences.isEmpty()) {
                    terms.merge(term.kind() + ":" + term.normalized(), new TermPayload.Entry(term.term(),
                            term.normalized(), term.kind(), occurrences.size(), term.expansion(), term.section(),
                            occurrences), LocalReaderAnalyses::mergeTerms);
                }
            }
            Map<Integer, List<String>> documentIds = ids.computeIfAbsent(slice.document(), key ->
                    DocumentPassages.sectionIds(key, own.stream().filter(result -> result.extraction() != null
                            && result.extraction().startsWith(basis)).toList()));
            List<String> sectionIds = documentIds.getOrDefault(slice.section(), List.of());
            Map<Integer, String> passageAt = new HashMap<>();
            if (sectionPassages.state() == PagePassages.State.CURRENT) {
                for (PagePassages.Passage passage : sectionPassages.passages()) {
                    if (!slice.contains(passage.start(), passage.end()) || passage.index() >= sectionIds.size()
                            || sectionIds.get(passage.index()) == null) {
                        continue;
                    }
                    String id = sectionIds.get(passage.index());
                    passageAt.put(passage.start(), id);
                    passages.add(new PagePassages.Passage(id, passages.size(), slice.offset() + passage.start(),
                            slice.offset() + passage.end(), passage.title(), passage.text(), passage.section(),
                            sentenceOffset + passage.firstSentence(), passage.sentences(), passage.kind(),
                            passage.boundary(), passage.similarity()));
                }
                if (sectionPassages.result() != null) {
                    sentenceOffset += PassagePayload.parse(sectionPassages.result().payloadJson()).sentences();
                }
                if (sectionIds.isEmpty() && !sectionPassages.passages().isEmpty()) {
                    passageState = merge(passageState, PagePassages.State.STALE);
                }
            }
            if (sectionNavigation.state() == PagePassages.State.CURRENT) {
                semanticNavigation |= sectionNavigation.analysis() == DocumentNavigationBuilder.Analysis.SEMANTIC;
            }
            String prefix = "s" + slice.offset() + "-";
            for (NavigationSection chapter : sectionNavigation.chapters()) {
                shift(chapter, slice, prefix, passageAt).ifPresent(chapters::add);
            }
        }
        List<String> notes = warnings.stream().distinct().toList();
        // Do not label incomplete or outdated section results as a current page analysis.
        PageAcronyms acronymView = new PageAcronyms(PageAcronyms.State.valueOf(acronymState.name()),
                firstAcronyms.result(), acronymState == PagePassages.State.CURRENT
                ? acronyms.values().stream().sorted(Comparator.comparing(PageAcronyms.Acronym::acronym)).toList()
                : List.of(), notes);
        PageTerms termView = new PageTerms(PageTerms.State.valueOf(termState.name()), firstTerms.result(),
                termState == PagePassages.State.CURRENT ? terms.values().stream()
                        .sorted(Comparator.comparing(TermPayload.Entry::normalized).thenComparing(TermPayload.Entry::kind))
                        .toList() : List.of(), notes);
        PagePassages passageView = new PagePassages(passageState, firstPassages.result(), firstPassages.mode(),
                firstPassages.reason(), firstPassages.model(), firstPassages.segmentation(),
                passageState == PagePassages.State.CURRENT ? passages : List.of(), notes);
        PageText text = reading.text();
        DocumentNavigationView navigationView = new DocumentNavigationView(navState,
                semanticNavigation && navState == PagePassages.State.CURRENT
                        ? DocumentNavigationBuilder.Analysis.SEMANTIC : DocumentNavigationBuilder.Analysis.HEADINGS,
                firstNavigation.reason(), navState == PagePassages.State.CURRENT ? chapters
                        : navigation.headingsOnly(text, null).chapters(), snapshot.metadata().title(),
                text.inputSha256(), PassagePayload.textSha256(text.text()));
        return new Result(text, acronymView, termView, passageView, navigationView);
    }

    private Enrichment.Freshness freshness(Enrichment result, LocalReadingText.Slice slice,
                                           Map<String, String> versions, String basis) {
        return result.extraction() != null && result.extraction().startsWith(basis)
                && !enrichments.outdated(result)
                ? result.freshness(slice.text().inputSha256(), versions.get(result.processorId()))
                : Enrichment.Freshness.STALE;
    }

    private static PagePassages.State merge(PagePassages.State left, PagePassages.State right) {
        if (left == PagePassages.State.FAILED || right == PagePassages.State.FAILED) {
            return PagePassages.State.FAILED;
        }
        if (left == PagePassages.State.STALE || right == PagePassages.State.STALE) {
            return PagePassages.State.STALE;
        }
        return left == PagePassages.State.MISSING || right == PagePassages.State.MISSING
                ? PagePassages.State.MISSING : PagePassages.State.CURRENT;
    }

    private static PageAcronyms.Acronym mergeAcronyms(PageAcronyms.Acronym first, PageAcronyms.Acronym second) {
        var occurrences = new ArrayList<>(first.occurrences());
        occurrences.addAll(second.occurrences());
        return new PageAcronyms.Acronym(first.acronym(), first.count() + second.count(),
                first.expansion() == null ? second.expansion() : first.expansion(), first.section(), occurrences);
    }

    private static TermPayload.Entry mergeTerms(TermPayload.Entry first, TermPayload.Entry second) {
        var occurrences = new ArrayList<>(first.occurrences());
        occurrences.addAll(second.occurrences());
        return new TermPayload.Entry(first.term(), first.normalized(), first.kind(), first.count() + second.count(),
                first.expansion() == null ? second.expansion() : first.expansion(), first.section(), occurrences);
    }

    private static Optional<NavigationSection> shift(NavigationSection section, LocalReadingText.Slice slice,
                                                      String prefix, Map<Integer, String> passageAt) {
        if (section.start() >= slice.length()) {
            return Optional.empty();
        }
        return Optional.of(new NavigationSection(prefix + section.id(), section.label(), section.origin(),
                section.level(), slice.offset() + section.start(), slice.offset() + Math.min(section.end(), slice.length()),
                section.anchor(), section.parentId() == null ? null : prefix + section.parentId(),
                section.children().stream().flatMap(child -> shift(child, slice, prefix, passageAt).stream()).toList(),
                section.origin() == NavigationSection.Origin.TOPIC ? passageAt.get(section.start()) : null));
    }

    public record Result(PageText text, PageAcronyms acronyms, PageTerms terms, PagePassages passages,
                         DocumentNavigationView navigation) {
    }
}
