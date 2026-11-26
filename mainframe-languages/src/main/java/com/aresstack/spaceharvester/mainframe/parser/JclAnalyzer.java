package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.parser.JclAnalysis.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Analyze logical cards in their declaration scopes. Never expand unknown procedures or symbols. */
public final class JclAnalyzer {
    private static final Pattern MEMBER = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}", Pattern.CASE_INSENSITIVE);

    public JclAnalysis analyze(String source, String content) {
        var deck = JclStatements.read(content);
        var diagnostics = new ArrayList<Diagnostic>();
        deck.issues().forEach(issue -> diagnostics.add(new Diagnostic(issue.line(), issue.message())));
        var statements = structure(deck.cards(), diagnostics);
        var dependencies = new ArrayList<Dependency>();
        for (var statement : statements) dependencies(statement, statements, dependencies, diagnostics);
        return new JclAnalysis(source, statements, dependencies, diagnostics);
    }

    private static List<Statement> structure(List<JclStatements.Card> cards, List<Diagnostic> diagnostics) {
        var result = new ArrayList<Statement>();
        var guards = new ArrayList<Guard>();
        int job = 0, procedure = 0, step = 0, dd = 0;
        for (var card : cards) {
            String op = card.operation();
            switch (op) {
                case "JOB", "END" -> {
                    if (procedure != 0 || !guards.isEmpty()) diagnostics.add(new Diagnostic(card.first(),
                            "Offener PROC- oder IF-Bereich am Jobende"));
                    job = "JOB".equals(op) ? card.first() : 0;
                    procedure = step = dd = 0;
                    guards.clear();
                }
                case "PROC" -> {
                    if (procedure != 0 || !guards.isEmpty()) diagnostics.add(new Diagnostic(card.first(),
                            "Verschachtelter PROC- oder IF-Bereich nicht aufgelöst"));
                    procedure = card.first();
                    step = dd = 0;
                    guards.clear();
                }
                case "EXEC" -> {
                    step = card.first();
                    dd = 0;
                }
                case "DD" -> {
                    if (!card.name().isEmpty()) dd = card.first();
                    else if (dd == 0) diagnostics.add(new Diagnostic(card.first(), "DD ohne zugehörige Verkettung"));
                    if (step == 0 && !Set.of("JOBLIB", "STEPLIB").contains(card.name())) {
                        diagnostics.add(new Diagnostic(card.first(), "DD ohne zugeordneten EXEC-Schritt"));
                    }
                }
                case "ELSE" -> {
                    if (guards.isEmpty() || guards.getLast().otherwise()) {
                        diagnostics.add(new Diagnostic(card.first(), "ELSE ohne passendes IF"));
                    } else {
                        var guard = guards.removeLast();
                        guards.add(new Guard(guard.ifLine(), guard.expression(), true));
                    }
                }
                default -> { }
            }
            if (!"DD".equals(op)) dd = 0;
            result.add(new Statement(card.first(), card.last(), card.name(), op, card.operands(), card.parameters(),
                    card.positional(), job, procedure, step, dd, guards, card.complete()));
            switch (op) {
                case "IF" -> {
                    if (!JclStatements.upper(card.operands()).endsWith(" THEN")) {
                        diagnostics.add(new Diagnostic(card.first(), "IF-Bedingung ohne eindeutiges THEN"));
                    }
                    guards.add(new Guard(card.first(), card.operands(), false));
                }
                case "ENDIF" -> {
                    if (guards.isEmpty()) diagnostics.add(new Diagnostic(card.first(), "ENDIF ohne passendes IF"));
                    else guards.removeLast();
                }
                case "PEND" -> {
                    if (procedure == 0) diagnostics.add(new Diagnostic(card.first(), "PEND ohne PROC"));
                    if (!guards.isEmpty()) diagnostics.add(new Diagnostic(card.first(), "IF-Bereich am PEND nicht abgeschlossen"));
                    procedure = step = dd = 0;
                    guards.clear();
                }
                default -> { }
            }
        }
        if (!guards.isEmpty()) diagnostics.add(new Diagnostic(guards.getLast().ifLine(), "IF-Bereich nicht abgeschlossen"));
        // A cataloged procedure member can omit PEND.
        return List.copyOf(result);
    }

    private static void dependencies(Statement s, List<Statement> statements,
                                     List<Dependency> result, List<Diagnostic> diagnostics) {
        if (!s.complete()) return;
        var params = s.parameters();
        switch (s.operation()) {
            case "EXEC" -> {
                String pgm = params.get("PGM");
                String proc = params.get("PROC");
                if (pgm != null && (proc != null || !s.positional().isEmpty())) {
                    diagnostics.add(new Diagnostic(s.line(), "Mehrdeutige EXEC-Zielangabe"));
                } else if (pgm != null) {
                    add(result, diagnostics, s, Kind.PROGRAM, pgm, "", "", 0);
                } else {
                    if (proc == null && !s.positional().isEmpty()) proc = s.positional().getFirst();
                    if (proc == null || proc.isBlank()) {
                        diagnostics.add(new Diagnostic(s.line(), "EXEC-Ziel fehlt"));
                    } else {
                        String target = JclStatements.unquote(proc);
                        var local = statements.stream().filter(candidate -> "PROC".equals(candidate.operation())
                                && candidate.jobLine() == s.jobLine() && candidate.line() < s.line()
                                && candidate.name().equalsIgnoreCase(target)).toList();
                        int line = local.size() == 1 ? local.getFirst().line() : 0;
                        add(result, diagnostics, s, Kind.PROCEDURE, proc, "", "", line);
                        diagnostics.add(new Diagnostic(s.line(), "PROC-Inhalt und Parameterüberschreibungen nicht expandiert"));
                    }
                }
                if (params.containsKey("COND")) diagnostics.add(new Diagnostic(s.line(),
                        "COND bestimmt die Ausführung zur Laufzeit; keine Auswertungsentscheidung"));
            }
            case "DD" -> dd(s, statements, result, diagnostics);
            case "INCLUDE" -> {
                add(result, diagnostics, s, Kind.INCLUDE, params.get("MEMBER"), "", "", 0);
                diagnostics.add(new Diagnostic(s.line(), "INCLUDE-Inhalt nicht expandiert"));
            }
            case "JCLLIB" -> {
                String order = params.get("ORDER");
                if (order != null) {
                    String list = order.startsWith("(") && order.endsWith(")") ? order.substring(1, order.length() - 1) : order;
                    for (String library : JclStatements.split(list)) {
                        add(result, diagnostics, s, Kind.PROCEDURE_LIBRARY, library.strip(), "", "", 0);
                    }
                }
            }
            case "SET", "PROC" -> {
                if (!params.isEmpty()) diagnostics.add(new Diagnostic(s.line(), "Symboldefinitionen gespeichert, Substitution nicht ausgeführt"));
            }
            default -> { }
        }
    }

    private static void dd(Statement s, List<Statement> statements,
                           List<Dependency> result, List<Diagnostic> diagnostics) {
        var params = s.parameters();
        String ddName = s.name();
        if (ddName.isEmpty() && s.concatenationLine() != 0) {
            ddName = statements.stream().filter(item -> item.line() == s.concatenationLine())
                    .findFirst().map(Statement::name).orElse("");
        }
        String disp = params.getOrDefault("DISP", "");
        String dataset = params.getOrDefault("DSN", params.get("DSNAME"));
        if (dataset != null) {
            String target = JclStatements.unquote(dataset);
            Kind kind = target.startsWith("&&") ? Kind.TEMPORARY_DATASET
                    : target.startsWith("*." ) ? Kind.DATASET_REFERENCE : Kind.DATASET;
            add(result, diagnostics, s, kind, dataset, ddName, disp, 0);
        } else if (params.containsKey("PATH")) {
            add(result, diagnostics, s, Kind.PATH, params.get("PATH"), ddName, disp, 0);
        } else if (params.containsKey("SYSOUT")) {
            add(result, diagnostics, s, Kind.SYSOUT, params.get("SYSOUT"), ddName, disp, 0);
        } else if (!s.positional().isEmpty()) {
            String operand = JclStatements.upper(s.positional().getFirst());
            if ("DUMMY".equals(operand)) add(result, diagnostics, s, Kind.DUMMY, operand, ddName, disp, 0);
            else if ("*".equals(operand) || "DATA".equals(operand)) {
                add(result, diagnostics, s, Kind.INSTREAM, operand, ddName, disp, 0);
            } else diagnostics.add(new Diagnostic(s.line(), "DD-Operand nicht aufgelöst"));
        }
        for (String reference : List.of("VOL", "VOLUME", "DCB", "LIKE", "REFDD")) {
            String value = params.get(reference);
            if (value != null && value.contains("*.")) {
                add(result, diagnostics, s, Kind.DATASET_REFERENCE, value, ddName, disp, 0);
            }
        }
        if (s.name().contains(".")) diagnostics.add(new Diagnostic(s.line(), "DD-Überschreibung einer Prozedur nicht expandiert"));
    }

    private static void add(List<Dependency> result, List<Diagnostic> diagnostics, Statement s, Kind kind,
                            String value, String dd, String disp, int declaration) {
        if (value == null || value.isBlank()) {
            diagnostics.add(new Diagnostic(s.line(), "Fehlendes Ziel für " + kind));
            return;
        }
        String target = JclStatements.unquote(value);
        Resolution resolution;
        if (kind == Kind.DATASET_REFERENCE) resolution = Resolution.REFERENCE;
        else if (kind == Kind.TEMPORARY_DATASET) resolution = Resolution.REFERENCE;
        else if (target.contains("&")) resolution = Resolution.SYMBOLIC;
        else if (declaration != 0) resolution = Resolution.LOCAL_DECLARATION;
        else if (Set.of(Kind.PROGRAM, Kind.PROCEDURE, Kind.INCLUDE).contains(kind) && !MEMBER.matcher(target).matches()) {
            resolution = Resolution.UNRESOLVED;
        } else resolution = Resolution.LITERAL;
        if (resolution == Resolution.SYMBOLIC || resolution == Resolution.UNRESOLVED || resolution == Resolution.REFERENCE) {
            diagnostics.add(new Diagnostic(s.line(), "Ziel nicht aufgelöst: " + target));
        }
        result.add(new Dependency(kind, resolution, target, s.line(), s.stepLine(), dd, disp, declaration));
    }
}
