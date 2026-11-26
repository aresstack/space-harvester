package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Adapt the shared outline parser into conservative persisted Natural facts. */
public final class NaturalAnalyzer {
    private static final Pattern CALL = Pattern.compile("^\\s*(CALLNAT|CALL|FETCH)\\s+(?:RETURN\\s+)?(?:'((?:[^']|'')*)'|\"((?:[^\"]|\"\")*)\"|(\\S+))", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORK = Pattern.compile("^\\s*(READ|WRITE|CLOSE)\\s+WORK\\s+FILE\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("[A-Z0-9#@$_.-]+", Pattern.CASE_INSENSITIVE);

    public NaturalAnalysis analyze(String library, String name, String extension, String code) {
        var elements = new ArrayList<Element>();
        var dependencies = new ArrayList<Dependency>();
        var diagnostics = new ArrayList<Diagnostic>();
        if ("NSD".equals(extension)) {
            ddm(name, code, elements, diagnostics);
        } else {
            var outline = new NaturalParser().parse(code, library + "/" + name + "." + extension);
            var items = outline.getElements();
            for (var item : items) {
                elements.add(new Element(item.getType().name(), item.getName(), item.getLineNumber(),
                        item.getParent() == null ? 0 : item.getParent().getLineNumber(), item.getParameters()));
                dependencies(item, items, dependencies, diagnostics);
            }
            // This parser is an outline parser, not a full Natural grammar or runtime resolver.
            diagnostics.add(new Diagnostic(0, "Strukturelle Teilanalyse: keine vollständige Syntax-, Kontroll- oder Datenflussanalyse; externe Bibliotheksauflösung fehlt."));
            if (items.isEmpty()) diagnostics.add(new Diagnostic(0, "Keine unterstützten Strukturelemente erkannt."));
        }
        return new NaturalAnalysis(library, name, extension, elements, dependencies, diagnostics);
    }

    private static void ddm(String name, String code, List<Element> elements, List<Diagnostic> diagnostics) {
        var ddm = new DdmParser().parse(code, name);
        if (ddm == null) {
            diagnostics.add(new Diagnostic(0, "DDM-Format nicht erkannt."));
            return;
        }
        elements.add(new Element("DDM_HEADER", ddm.getName(), 0, 0, Map.of(
                "DB", Integer.toString(ddm.getDbId()), "FNR", Integer.toString(ddm.getFileNumber()),
                "DEFAULT_SEQ", java.util.Objects.toString(ddm.getDefaultSequence(), ""))));
        var levels = new java.util.TreeMap<Integer, Integer>();
        for (var field : ddm.getFields()) {
            int level = field.getLevel();
            levels.tailMap(level, true).clear();
            var parent = levels.lowerEntry(level);
            var attributes = new LinkedHashMap<String, String>();
            attributes.put("SHORT_NAME", field.getShortName());
            attributes.put("LEVEL", Integer.toString(level));
            attributes.put("FORMAT", java.util.Objects.toString(field.getFormatSpec(), ""));
            attributes.put("TYPE", Character.toString(field.getTypeIndicator()));
            attributes.put("DESCRIPTOR", Boolean.toString(field.isDescriptor()));
            attributes.put("SUPERDESCRIPTOR", Boolean.toString(field.isSuperdescriptor()));
            elements.add(new Element("DDM_FIELD", field.getLongName(), field.getSourceLine(),
                    parent == null ? 0 : parent.getValue(), attributes));
            levels.put(level, field.getSourceLine());
        }
        diagnostics.add(new Diagnostic(0, "DDM-Felddeklarationen; keine Verifikation gegen den physischen Adabas-Bestand."));
    }

    private static void dependencies(JclElement item, List<JclElement> items,
                                     List<Dependency> result, List<Diagnostic> diagnostics) {
        int line = item.getLineNumber();
        var kind = item.getType();
        String raw = item.getRawText();
        switch (kind) {
            case NAT_CALLNAT, NAT_CALL, NAT_FETCH -> {
                var match = CALL.matcher(raw);
                if (!match.find()) {
                    diagnostics.add(new Diagnostic(line, "Aufrufziel nicht erkannt."));
                    return;
                }
                String literal = match.group(2) != null ? match.group(2).replace("''", "'")
                        : match.group(3) != null ? match.group(3).replace("\"\"", "\"") : null;
                Kind call = Kind.valueOf(match.group(1).toUpperCase(Locale.ROOT));
                result.add(new Dependency(call, literal == null ? match.group(4) : literal,
                        literal == null ? Resolution.DYNAMIC : NAME.matcher(literal).matches()
                                ? Resolution.LITERAL : Resolution.UNRESOLVED, line, raw, 0));
            }
            case NAT_PERFORM -> {
                String target = item.getParameter("TARGET");
                var local = items.stream().filter(e -> e.getType() == JclElementType.NAT_INLINE_SUBROUTINE
                        && e.getName().equalsIgnoreCase(target)).toList();
                result.add(new Dependency(Kind.PERFORM, target, local.size() == 1
                        ? Resolution.LOCAL_DECLARATION : Resolution.UNRESOLVED, line, raw,
                        local.size() == 1 ? local.getFirst().getLineNumber() : 0));
            }
            case NAT_INCLUDE -> add(result, Kind.COPYCODE, item.getParameter("COPYCODE"), line, raw);
            case NAT_LOCAL, NAT_PARAMETER, NAT_GLOBAL, NAT_INDEPENDENT ->
                    add(result, Kind.DATA_AREA, item.getParameter("USING"), line, kind.name());
            case NAT_DATA_VIEW -> add(result, Kind.DDM, item.getParameter("OF"), line, item.getName());
            case NAT_INPUT, NAT_WRITE -> {
                var work = WORK.matcher(raw);
                if (work.find()) add(result, Kind.WORK_FILE, work.group(2), line, raw);
                // Do not infer map targets from arbitrary quoted output text.
                else if (raw.matches("(?i)^\\s*(?:INPUT|WRITE)\\s+(?:USING\\s+)?(?:MAP|FORM)\\s+.*")) {
                    add(result, Kind.MAP, item.getParameter("MAP"), line, raw);
                }
            }
            case NAT_READ, NAT_FIND, NAT_HISTOGRAM, NAT_STORE, NAT_UPDATE, NAT_DELETE, NAT_GET -> {
                var work = WORK.matcher(raw);
                if (work.find()) {
                    add(result, Kind.WORK_FILE, work.group(2), line, raw);
                    return;
                }
                String target = item.getParameter("FILE");
                var views = items.stream().filter(e -> e.getType() == JclElementType.NAT_DATA_VIEW
                        && e.getName().equalsIgnoreCase(target)).toList();
                result.add(new Dependency(Kind.DATABASE, target == null ? "" : target,
                        views.size() == 1 ? Resolution.DECLARED_VIEW : Resolution.UNRESOLVED,
                        line, raw, views.size() == 1 ? views.getFirst().getLineNumber() : 0));
            }
            default -> { }
        }
    }

    private static void add(List<Dependency> result, Kind kind, String target, int line, String detail) {
        if (target == null || target.isBlank()) return;
        result.add(new Dependency(kind, target, NAME.matcher(target).matches()
                ? Resolution.LITERAL : Resolution.UNRESOLVED, line, detail, 0));
    }
}
