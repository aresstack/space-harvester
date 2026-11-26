package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow;
import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow.*;
import io.proleap.cobol.CobolBaseVisitor;
import io.proleap.cobol.CobolParser.*;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Project explicit syntax-tree control transfers, never infer adjacency from an outline. */
final class CobolControlFlowBuilder extends CobolBaseVisitor<Void> {
    private final MappedCobolText source;
    private final CommonTokenStream tokens;
    private final List<Procedure> procedures = new ArrayList<>();
    private final List<Limitation> limitations = new ArrayList<>();

    CobolControlFlowBuilder(MappedCobolText source, CommonTokenStream tokens) {
        this.source = source;
        this.tokens = tokens;
    }

    CobolControlFlow build(StartRuleContext tree) {
        visit(tree);
        return new CobolControlFlow(procedures, limitations);
    }

    @Override public Void visitProgramUnit(ProgramUnitContext unit) {
        if (unit.procedureDivision() != null) {
            String name = unit.identificationDivision().programIdParagraph().programName().getText();
            procedures.add(new ProcedureBuilder(name, unit.procedureDivision()).build());
        }
        for (var nested : unit.programUnit()) visit(nested);
        return null;
    }

    private final class ProcedureBuilder {
        private record Block(String name, String section, String entry, String boundary,
                             List<SentenceContext> sentences, ParagraphContext paragraph) { }
        private record Target(String entry, int end) { }
        private final String name;
        private final ProcedureDivisionContext division;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final List<Block> blocks = new ArrayList<>();
        private final boolean altered;
        private String exit;

        ProcedureBuilder(String name, ProcedureDivisionContext division) {
            this.name = name;
            this.division = division;
            this.altered = contains(division, AlterStatementContext.class);
        }

        Procedure build() {
            String entry = node(NodeKind.ENTRY, name, division);
            exit = node(NodeKind.EXIT, "PROCEDURE DIVISION end", division);
            if (division.procedureDeclaratives() != null) {
                unknown(entry, division.procedureDeclaratives(), "DECLARATIVES: exceptional entry and return not modeled");
            }
            if (contains(division, GoToStatementContext.class) && contains(division, PerformStatementContext.class)) {
                limitations.add(new Limitation("GO TO crossing an active PERFORM range requires runtime context",
                        source.at(division.getStart())));
            }
            collectBlocks();
            edge(entry, blocks.isEmpty() ? exit : blocks.getFirst().entry(), EdgeKind.NEXT);
            String next = exit;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Block block = blocks.get(i);
                linkBlock(block, next);
                next = block.entry();
            }
            return new Procedure(name, entry, exit, nodes, edges);
        }

        private void collectBlocks() {
            var body = division.procedureDivisionBody();
            addParagraphs(body.paragraphs(), "", null);
            for (var section : body.procedureSection()) {
                addParagraphs(section.paragraphs(), section.procedureSectionHeader().sectionName().getText(),
                        section.procedureSectionHeader());
            }
        }

        private void addParagraphs(ParagraphsContext paragraphs, String section, ParserRuleContext header) {
            if (header != null || !paragraphs.sentence().isEmpty()) {
                addBlock("", section, header == null ? paragraphs : header, paragraphs.sentence(), null);
            }
            for (var paragraph : paragraphs.paragraph()) {
                addBlock(paragraph.paragraphName().getText(), section, paragraph.paragraphName(),
                        paragraph.sentence(), paragraph);
            }
        }

        private void addBlock(String label, String section, ParserRuleContext header,
                              List<SentenceContext> sentences, ParagraphContext paragraph) {
            String display = label.isEmpty() ? (section.isEmpty() ? "Procedure body" : section + " SECTION") : label;
            String start = node(NodeKind.LABEL, display, header);
            ParserRuleContext end = sentences.isEmpty() ? header : sentences.getLast();
            String boundary = nodeId();
            nodes.add(new Node(boundary, NodeKind.BOUNDARY, "End of " + display,
                    source.span(end.getStop(), end.getStop())));
            blocks.add(new Block(label, section, start, boundary, List.copyOf(sentences), paragraph));
        }

        private void linkBlock(Block block, String next) {
            edge(block.boundary(), next, EdgeKind.FALLTHROUGH, "no matching active PERFORM return", "");
            if (block.paragraph() != null && block.paragraph().alteredGoTo() != null) {
                unknown(block.entry(), block.paragraph().alteredGoTo(), "ALTER-dependent GO TO");
                return;
            }
            String continuation = block.boundary();
            var sentences = block.sentences();
            for (int i = sentences.size() - 1; i >= 0; i--) {
                continuation = sequence(sentences.get(i).statement(), continuation, continuation);
            }
            edge(block.entry(), continuation, EdgeKind.NEXT);
        }

        private String sequence(List<StatementContext> statements, String next, String nextSentence) {
            String first = next;
            for (int i = statements.size() - 1; i >= 0; i--) first = statement(statements.get(i), first, nextSentence);
            return first;
        }

        private String statement(StatementContext context, String next, String nextSentence) {
            if (source.at(context.getStart()).debuggingLine()) {
                String gate = node(NodeKind.DECISION, "Debugging line", context);
                edge(gate, next, EdgeKind.FALSE, "debugging disabled", "");
                edge(gate, executableStatement(context, next, nextSentence), EdgeKind.TRUE, "debugging enabled", "");
                limitations.add(new Limitation("Debugging mode not determined", source.at(context.getStart())));
                return gate;
            }
            return executableStatement(context, next, nextSentence);
        }

        private String executableStatement(StatementContext context, String next, String nextSentence) {
            if (context.ifStatement() != null) return conditional(context.ifStatement(), next, nextSentence);
            if (context.evaluateStatement() != null) return evaluate(context.evaluateStatement(), next, nextSentence);
            if (context.performStatement() != null) return perform(context.performStatement(), next, nextSentence);
            if (context.goToStatement() != null) return goTo(context.goToStatement(), next);
            if (context.callStatement() != null) return call(context.callStatement(), next, nextSentence);
            String id = node(NodeKind.STATEMENT, text(context), context);
            if (context.gobackStatement() != null
                    || (context.stopStatement() != null && (context.stopStatement().RUN() != null
                            || context.stopStatement().stopStatementGiving() != null))) {
                edge(id, exit, EdgeKind.TERMINATE);
            } else if (context.exitStatement() != null && context.exitStatement().PROGRAM() != null) {
                edge(id, exit, EdgeKind.TERMINATE, "if called as a subprogram", "");
                edge(id, next, EdgeKind.NEXT, "if EXIT PROGRAM is ineffective in main program", "");
                limitations.add(new Limitation("EXIT PROGRAM depends on invocation context", source.at(context.getStart())));
            } else if (context.nextSentenceStatement() != null) {
                edge(id, nextSentence, EdgeKind.NEXT_SENTENCE);
            } else if (simple(context)) {
                edge(id, next, EdgeKind.NEXT);
            } else {
                unknown(id, context, "Control effects not modeled: " + context.getStart().getText());
            }
            return id;
        }

        private boolean simple(StatementContext context) {
            // Limit fall-through to statements without unmodeled nested branches or external transfers.
            String verb = normalize(context.getStart().getText());
            return Set.of("MOVE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE", "CONTINUE",
                    "DISPLAY", "INITIALIZE", "SET", "INSPECT", "EXIT", "STRING", "UNSTRING").contains(verb)
                    && !hasNestedStatement(context)
                    && !contains(context, EntryStatementContext.class);
        }

        private String conditional(IfStatementContext ctx, String next, String nextSentence) {
            String id = node(NodeKind.DECISION, "IF " + text(ctx.condition()), ctx);
            var then = ctx.ifThen();
            String yes = then.NEXT() == null ? sequence(then.statement(), next, nextSentence) : nextSentence;
            var otherwise = ctx.ifElse();
            String no = otherwise == null ? next : otherwise.NEXT() == null
                    ? sequence(otherwise.statement(), next, nextSentence) : nextSentence;
            edge(id, yes, EdgeKind.TRUE);
            edge(id, no, EdgeKind.FALSE);
            return id;
        }

        private String evaluate(EvaluateStatementContext ctx, String next, String nextSentence) {
            String id = node(NodeKind.DECISION, "EVALUATE " + text(ctx.evaluateSelect()) +
                    ctx.evaluateAlsoSelect().stream().map(item -> " " + text(item)).collect(java.util.stream.Collectors.joining()), ctx);
            for (var when : ctx.evaluateWhenPhrase()) {
                String condition = when.evaluateWhen().stream().map(CobolControlFlowBuilder.this::text)
                        .collect(java.util.stream.Collectors.joining(" / "));
                edge(id, sequence(when.statement(), next, nextSentence), EdgeKind.WHEN,
                        condition + " (no preceding WHEN matches)", "");
            }
            var other = ctx.evaluateWhenOther();
            edge(id, other == null ? next : sequence(other.statement(), next, nextSentence),
                    EdgeKind.OTHERWISE, "no preceding WHEN matches", "");
            return id;
        }

        private String perform(PerformStatementContext ctx, String next, String nextSentence) {
            String id = node(NodeKind.PERFORM, "PERFORM", ctx);
            var inline = ctx.performInlineStatement();
            if (inline != null) {
                if (inline.performType() == null) {
                    edge(id, sequence(inline.statement(), next, nextSentence), EdgeKind.NEXT);
                } else {
                    Loop loop = loop(inline.performType(), next);
                    if (loop == null) unknown(id, ctx, "Unsupported PERFORM loop");
                    else {
                        String body = sequence(inline.statement(), loop.test(), nextSentence);
                        connectLoop(id, loop, body);
                    }
                }
                return id;
            }
            var out = ctx.performProcedureStatement();
            var start = resolve(out.procedureName(0));
            var end = out.procedureName().size() == 1 ? start : resolve(out.procedureName(1));
            if (start == null || end == null || blocks.indexOf(blockFor(start.entry())) > end.end()) {
                unknown(id, ctx, "Unresolved or invalid PERFORM target/range: " + text(out));
                return id;
            }
            String invoke = node(NodeKind.PERFORM, text(out), out);
            String continuation = next;
            if (out.performType() == null) {
                edge(id, invoke, EdgeKind.NEXT);
            } else {
                Loop loop = loop(out.performType(), next);
                if (loop == null) {
                    unknown(id, ctx, "Unsupported out-of-line PERFORM loop");
                    return id;
                }
                connectLoop(id, loop, invoke);
                continuation = loop.test();
            }
            edge(invoke, start.entry(), EdgeKind.PERFORM_CALL, "", invoke);
            edge(blocks.get(end.end()).boundary(), continuation, EdgeKind.PERFORM_RETURN,
                    "matching active PERFORM", invoke);
            return id;
        }

        private record Loop(String test, boolean after, String bodyCondition, String exitCondition) { }

        private Loop loop(PerformTypeContext type, String next) {
            boolean after = false;
            String bodyCondition;
            String exitCondition;
            if (type.performTimes() != null) {
                bodyCondition = "iterations remaining";
                exitCondition = "iteration count exhausted";
            } else if (type.performUntil() != null) {
                var until = type.performUntil();
                after = until.performTestClause() != null && until.performTestClause().AFTER() != null;
                bodyCondition = "UNTIL condition false";
                exitCondition = "UNTIL condition true";
            } else if (type.performVarying() != null) {
                var varying = type.performVarying();
                if (!varying.performVaryingClause().performAfter().isEmpty()) return null;
                after = varying.performTestClause() != null && varying.performTestClause().AFTER() != null;
                bodyCondition = "VARYING condition false";
                exitCondition = "VARYING condition true";
            } else return null;
            String test = node(NodeKind.DECISION, text(type), type);
            edge(test, next, EdgeKind.LOOP_EXIT, exitCondition, "");
            return new Loop(test, after, bodyCondition, exitCondition);
        }

        private void connectLoop(String id, Loop loop, String body) {
            edge(id, loop.after() ? body : loop.test(), EdgeKind.NEXT);
            edge(loop.test(), body, EdgeKind.LOOP_BODY, loop.bodyCondition(), "");
        }

        private String goTo(GoToStatementContext ctx, String next) {
            String id = node(NodeKind.STATEMENT, text(ctx), ctx);
            if (altered) {
                unknown(id, ctx, "GO TO in a procedure containing ALTER");
            } else if (ctx.goToStatementSimple() != null) {
                transfer(id, ctx.goToStatementSimple().procedureName(), EdgeKind.GOTO, "");
            } else {
                var depending = ctx.goToDependingOnStatement();
                if (depending.identifier() == null || depending.procedureName().isEmpty()) {
                    unknown(id, ctx, "Unsupported GO TO target");
                } else {
                    int ordinal = 0;
                    for (var target : depending.procedureName()) {
                        transfer(id, target, EdgeKind.DEPENDING_TARGET, "index = " + ++ordinal);
                    }
                    edge(id, next, EdgeKind.OTHERWISE, "index outside target range", "");
                }
            }
            return id;
        }

        private void transfer(String id, ProcedureNameContext name, EdgeKind kind, String condition) {
            Target target = resolve(name);
            if (target == null) unknown(id, name, "Unresolved or ambiguous procedure target: " + text(name));
            else edge(id, target.entry(), kind, condition, "");
        }

        private Target resolve(ProcedureNameContext name) {
            String target = normalize(name.paragraphName() != null ? name.paragraphName().getText() : name.sectionName().getText());
            String section = name.inSection() == null ? "" : normalize(name.inSection().sectionName().getText());
            List<Target> candidates = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                var block = blocks.get(i);
                if (!section.isEmpty() && !section.equals(normalize(block.section()))) continue;
                if (!block.name().isEmpty() && target.equals(normalize(block.name()))) {
                    candidates.add(new Target(block.entry(), i));
                }
                if (section.isEmpty() && block.name().isEmpty() && !block.section().isEmpty()
                        && target.equals(normalize(block.section()))) {
                    int last = i;
                    while (last + 1 < blocks.size() && blocks.get(last + 1).section().equals(block.section())) last++;
                    candidates.add(new Target(block.entry(), last));
                }
            }
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        private Block blockFor(String entry) {
            return blocks.stream().filter(block -> block.entry().equals(entry)).findFirst().orElseThrow();
        }

        private String call(CallStatementContext ctx, String next, String nextSentence) {
            String target = ctx.literal() != null ? text(ctx.literal()) : text(ctx.identifier());
            String id = node(NodeKind.CALL, "CALL " + target, ctx);
            var normal = ctx.notOnExceptionClause();
            edge(id, normal == null ? next : sequence(normal.statement(), next, nextSentence),
                    EdgeKind.CALL_RETURN, "if the called program returns normally", "");
            var exceptional = ctx.onExceptionClause();
            if (exceptional != null) edge(id, sequence(exceptional.statement(), next, nextSentence),
                    EdgeKind.CALL_RETURN, "ON EXCEPTION", "");
            if (ctx.onOverflowPhrase() != null) unknown(id, ctx.onOverflowPhrase(), "CALL ON OVERFLOW not modeled");
            unknown(id, ctx, "External CALL effects and non-return not modeled: " + target);
            return id;
        }

        private String node(NodeKind kind, String label, ParserRuleContext ctx) {
            String id = nodeId();
            nodes.add(new Node(id, kind, label, source.span(ctx.getStart(), ctx.getStop()),
                    ctx.getStart().getTokenIndex()));
            return id;
        }

        private String nodeId() {
            return "n" + division.getStart().getTokenIndex() + "_" + nodes.size();
        }

        private void edge(String from, String to, EdgeKind kind) {
            edge(from, to, kind, "", "");
        }

        private void edge(String from, String to, EdgeKind kind, String condition, String site) {
            edges.add(new Edge(from, to, kind, condition, site));
        }

        private void unknown(String from, ParserRuleContext ctx, String message) {
            String target = node(NodeKind.UNKNOWN, message, ctx);
            edge(from, target, EdgeKind.UNKNOWN);
            limitations.add(new Limitation(message, source.at(ctx.getStart())));
        }
    }

    private static boolean hasNestedStatement(ParseTree tree) {
        for (int i = 0; i < tree.getChildCount(); i++) {
            var child = tree.getChild(i);
            if (child instanceof StatementContext || hasNestedStatement(child)) return true;
        }
        return false;
    }

    private static boolean contains(ParseTree tree, Class<?> type) {
        if (type.isInstance(tree)) return true;
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (contains(tree.getChild(i), type)) return true;
        }
        return false;
    }

    private String text(ParserRuleContext ctx) {
        return tokens.getText(ctx.getSourceInterval()).strip();
    }

    private static String normalize(String value) { return value.toUpperCase(Locale.ROOT); }
}
