package com.aresstack.spaceharvester.parity;

import com.aresstack.spaceharvester.parity.ParityComparison.Finding;
import com.aresstack.spaceharvester.parity.ParityComparison.Verdict;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bericht für den Live-Lauf (docs/PARITY.md, „Vergleichstests“): vergleicht Exporte desselben Space aus dem
 * Enterprise AI Client und aus Space Harvester.
 *
 * <pre>
 * parity-report &lt;eac-datei&gt; &lt;core-datei&gt; [&lt;eac-datei&gt; &lt;core-datei&gt; …] [--out bericht.md]
 * </pre>
 *
 * <p>Das Format folgt der Dateiendung ({@code .docx}, {@code .pdf}, {@code .md}). Der Bericht geht als Markdown auf
 * die Konsole und mit {@code --out} zusätzlich in eine Datei. Rückgabewert 1, wenn der Core irgendwo weniger kann.</p>
 */
public final class ParityReport {

    private static final Map<Verdict, String> LABELS = Map.of(
            Verdict.GLEICH, "gleich",
            Verdict.CORE_MEHR, "Core mehr",
            Verdict.CORE_WENIGER, "**Core weniger**",
            Verdict.NICHT_MESSBAR, "nicht messbar");

    private ParityReport() {
    }

    public static void main(String[] args) throws IOException {
        List<Path> files = new ArrayList<>();
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) {
                out = Path.of(args[++i]);
            } else {
                files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty() || files.size() % 2 != 0) {
            System.err.println("Aufruf: parity-report <eac-datei> <core-datei> [<eac-datei> <core-datei> …]"
                    + " [--out bericht.md]");
            System.exit(2);
            return;
        }
        StringBuilder report = new StringBuilder("# Paritätsbericht Enterprise AI Client gegen Space Harvester\n");
        boolean weaker = false;
        for (int i = 0; i < files.size(); i += 2) {
            List<Finding> findings = ParityComparison.compare(read(files.get(i)), read(files.get(i + 1)));
            weaker |= findings.stream().anyMatch(finding -> finding.verdict() == Verdict.CORE_WENIGER);
            report.append('\n').append(section(files.get(i).getFileName() + " ↔ " + files.get(i + 1).getFileName(),
                    findings, Map.of()));
        }
        System.out.print(report);
        if (out != null) {
            Files.writeString(out, report, StandardCharsets.UTF_8);
        }
        System.exit(weaker ? 1 : 0);
    }

    /** Liest eine Exportdatei nach ihrer Endung. */
    public static DocumentOutline read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return DocxReader.read(file);
        }
        if (name.endsWith(".pdf")) {
            return PdfReader.read(file);
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return MarkdownReader.read(file);
        }
        throw new IllegalArgumentException("Unbekanntes Format: " + file);
    }

    /**
     * Ein Abschnitt des Berichts.
     *
     * @param gaps bekannte Lücken: Schlüssel {@code <format>.<prüfung>} → Issue, z. B. {@code #175}
     */
    public static String section(String title, List<Finding> findings, Map<String, String> gaps) {
        StringBuilder text = new StringBuilder("## ").append(title).append("\n\n")
                .append("| Prüfung | Enterprise AI Client | Core | Ergebnis | Hinweis |\n")
                .append("|---|---|---|---|---|\n");
        for (Finding finding : findings) {
            String hint = finding.detail();
            String issue = gaps.get(finding.key());
            if (issue != null) {
                hint = (finding.verdict() == Verdict.CORE_WENIGER ? "bekannte Lücke " + issue
                        : "Lücke " + issue + " geschlossen, aus known-gaps entfernen")
                        + (hint.isEmpty() ? "" : "; " + hint);
            }
            text.append("| ").append(finding.title()).append(" | ").append(cell(finding.eac())).append(" | ")
                    .append(cell(finding.core())).append(" | ").append(LABELS.get(finding.verdict())).append(" | ")
                    .append(cell(hint)).append(" |\n");
        }
        return text.toString();
    }

    private static String cell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
