package com.aresstack.spaceharvester.app.ui.deck.editor;

import com.aresstack.spaceharvester.deck.editor.DocumentException;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalyzer;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.cobol.CopybookResolver;
import com.aresstack.spaceharvester.mainframe.dependency.SourceRelations;
import com.aresstack.spaceharvester.mainframe.outline.Outline;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Analyze one editor snapshot and share the result between structure and relations. */
final class CobolEditorAnalysis {
    record Result(Outline outline, SourceRelations.Analysis relations) { }

    private final CobolAnalyzer analyzer;
    private final CopybookResolver resolver;
    private final String location;
    private final long revision;
    private String analyzedText;
    private Result analyzedResult;

    CobolEditorAnalysis(DocumentStore store, String location, long revision) {
        this.analyzer = CobolAnalyzer.proLeap();
        this.resolver = new EditorCopybookResolver(store);
        this.location = location;
        this.revision = revision;
    }

    boolean matches(String currentLocation, long currentRevision) {
        return location.equals(currentLocation) && revision == currentRevision;
    }

    synchronized Result analyze(String text, SourceRelations relations) {
        if (analyzedResult != null && text.equals(analyzedText)) {
            return analyzedResult;
        }
        CobolSource source = CobolSource.fixed(location, text);
        CobolAnalysis analysis = analyzer.analyze(source, resolver);
        analyzedResult = new Result(Outline.fromCobolAnalysis(source, analysis), relations.analyzeCobol(source, analysis));
        analyzedText = text;
        return analyzedResult;
    }

    /** Cache positive and negative sibling resolutions for a loaded editor revision. */
    private static final class EditorCopybookResolver implements CopybookResolver {
        private record Key(String scope, String name, String library, CobolSource.Format format) { }
        private record Resolution(Optional<CobolSource> copybook, String error) { }

        private final DocumentStore store;
        private final ConcurrentHashMap<Key, Resolution> cache = new ConcurrentHashMap<>();

        EditorCopybookResolver(DocumentStore store) {
            this.store = store;
        }

        @Override
        public Optional<CobolSource> resolve(CobolSource including, String name, String library) throws IOException {
            Key key = new Key(store.copybookLookupScope(including.name(), library), name, library, including.format());
            Resolution resolution = cache.computeIfAbsent(key, ignored -> load(including, name, library));
            if (resolution.error() != null) {
                throw new IOException(resolution.error());
            }
            return resolution.copybook();
        }

        private Resolution load(CobolSource including, String name, String library) {
            if (!safeName(name)) {
                return failure("Unsupported COPY name: " + name);
            }
            try {
                return new Resolution(store.resolveCopybook(including.name(), name, library)
                        .map(document -> new CobolSource(document.location(), document.text(), including.format())), null);
            } catch (DocumentException error) {
                return failure(error.getMessage());
            }
        }

        private boolean safeName(String name) {
            return !name.isBlank() && !name.contains("/") && !name.contains("\\")
                    && !name.contains("(") && !name.contains(")") && !name.contains("'")
                    && !name.contains("\"") && !name.equals(".") && !name.equals("..");
        }

        private Resolution failure(String error) {
            return new Resolution(Optional.empty(), error);
        }
    }
}
