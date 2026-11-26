package com.aresstack.spaceharvester.mainframe.parser;

import java.util.List;
import java.util.Map;

/** Source-mapped structural facts; no execution or resolved external library binding. */
public record NaturalAnalysis(String library, String name, String extension, List<Element> elements,
                              List<Dependency> dependencies, List<Diagnostic> diagnostics) {
    public NaturalAnalysis {
        elements = List.copyOf(elements);
        dependencies = List.copyOf(dependencies);
        diagnostics = List.copyOf(diagnostics);
    }
    public record Element(String kind, String name, int line, int parentLine, Map<String, String> attributes) {
        public Element { attributes = Map.copyOf(attributes); }
    }
    public enum Kind { CALLNAT, CALL, FETCH, PERFORM, COPYCODE, DATA_AREA, MAP, DDM, DATABASE, WORK_FILE }
    public enum Resolution { LITERAL, DYNAMIC, LOCAL_DECLARATION, DECLARED_VIEW, UNRESOLVED }
    public record Dependency(Kind kind, String target, Resolution resolution, int line, String detail, int declarationLine) { }
    public record Diagnostic(int line, String message) { }
}
