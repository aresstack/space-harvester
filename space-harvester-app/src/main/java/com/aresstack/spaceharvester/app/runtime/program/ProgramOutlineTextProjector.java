package com.aresstack.spaceharvester.app.runtime.program;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpSourceProvider;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.NdvSourceProvider;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.outline.Outline;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gliederung von Großrechner-Quelltext aus FTP und NDV als Abschnitte des Lesetexts: dieselben Parser wie das Outline
 * des Code-Viewers ({@link Outline}, aus MainframeMate). Überschriften sind die tragenden Teile (Job, Prozedur, Step;
 * Division, Section, Paragraph; DEFINE DATA, Subroutine), nicht jede Anweisung. Text und Schema bleiben die der
 * eingebauten Projektion; die Abschnitte folgen allein aus dem Text und ändern den Stand bestehender Ergebnisse nicht.
 */
public final class ProgramOutlineTextProjector implements PageTextProjector {

    /** Ebene je tragendem Element; was fehlt, ist keine Überschrift. */
    private static final Map<JclElementType, Integer> LEVELS = Map.ofEntries(
            Map.entry(JclElementType.JOB, 1), Map.entry(JclElementType.PROC, 1), Map.entry(JclElementType.EXEC, 2),
            Map.entry(JclElementType.DIVISION, 1), Map.entry(JclElementType.PROCEDURE_DIVISION, 1),
            Map.entry(JclElementType.SECTION, 2), Map.entry(JclElementType.WORKING_STORAGE, 2),
            Map.entry(JclElementType.LINKAGE_SECTION, 2), Map.entry(JclElementType.FILE_SECTION, 2),
            Map.entry(JclElementType.SCREEN_SECTION, 2), Map.entry(JclElementType.PARAGRAPH, 3),
            Map.entry(JclElementType.NAT_DEFINE_DATA, 1), Map.entry(JclElementType.NAT_SUBROUTINE, 1),
            Map.entry(JclElementType.NAT_INLINE_SUBROUTINE, 1));

    @Override
    public Optional<PageText> project(PageSnapshot snapshot) {
        String format = snapshot.payload().nativeFormat();
        String schema;
        if (FtpSourceProvider.SOURCE_TEXT_FORMAT.equals(format)) {
            schema = "ftp-source-text/v1";
        } else if (NdvSourceProvider.SOURCE_TEXT_FORMAT.equals(format)) {
            schema = "ndv-source-text/v1";
        } else {
            return Optional.empty();
        }
        String code = new String(snapshot.payload().bytes(), StandardCharsets.UTF_8);
        if (code.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new PageText(schema, code, sections(snapshot.metadata().title(), code), true));
    }

    /** Die Abschnitte des Quelltexts; leer, wenn die Sprache unbekannt ist oder der Parser scheitert. */
    static List<PageText.Section> sections(String name, String code) {
        Optional<SourceLanguage> language = SourceLanguage.detect(name == null ? "" : name, code);
        if (language.isEmpty()) {
            return List.of();
        }
        List<JclElement> elements;
        try {
            elements = Outline.parse(language.get(), code, name).model().getElements();
        } catch (RuntimeException e) {
            // Ohne Gliederung bleibt der Text lesbar und durchsuchbar.
            return List.of();
        }
        int[] lineStarts = lineStarts(code);
        List<int[]> found = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        for (JclElement element : elements) {
            Integer level = LEVELS.get(element.getType());
            int line = element.getLineNumber();
            String heading = element.getName() == null ? "" : element.getName().strip();
            if (level == null || heading.isEmpty() || line < 1 || line > lineStarts.length) {
                continue;
            }
            found.add(new int[] {lineStarts[line - 1], level, headings.size()});
            headings.add(heading);
        }
        found.sort(Comparator.comparingInt((int[] f) -> f[0]).thenComparingInt(f -> f[1]));
        List<PageText.Section> sections = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            int end = code.length();
            for (int next = i + 1; next < found.size(); next++) {
                if (found.get(next)[1] <= found.get(i)[1]) {
                    end = found.get(next)[0];
                    break;
                }
            }
            sections.add(new PageText.Section(headings.get(found.get(i)[2]), found.get(i)[1], found.get(i)[0],
                    Math.max(found.get(i)[0], end)));
        }
        return List.copyOf(sections);
    }

    private static int[] lineStarts(String code) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
