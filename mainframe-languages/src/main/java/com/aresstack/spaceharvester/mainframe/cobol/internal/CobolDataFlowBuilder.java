package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Span;
import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow;
import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow;
import com.aresstack.spaceharvester.mainframe.cobol.CobolReachingDefinitions;
import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow.*;
import io.proleap.cobol.CobolBaseVisitor;
import io.proleap.cobol.CobolParser.*;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Classify operands by grammar role. Keep uncertain effects separate from value dependencies. */
final class CobolDataFlowBuilder extends CobolBaseVisitor<Void> {
    private final MappedCobolText source;
    private final CommonTokenStream tokens;
    private final CobolDataSymbols symbols;
    private final CobolControlFlow controlFlow;
    private final Map<Integer, List<CobolControlFlow.Node>> controlNodes;
    private final List<Operation> operations = new ArrayList<>();
    private final List<Limitation> limitations = new ArrayList<>();

    CobolDataFlowBuilder(MappedCobolText source, CommonTokenStream tokens,
                         CobolDataSymbols symbols, CobolControlFlow controlFlow) {
        this.source = source;
        this.tokens = tokens;
        this.symbols = symbols;
        this.controlFlow = controlFlow;
        controlNodes = controlFlow.procedures().stream().flatMap(graph -> graph.nodes().stream())
                .filter(node -> switch (node.kind()) {
                    case STATEMENT, DECISION, CALL, PERFORM -> true;
                    default -> false;
                }).collect(Collectors.groupingBy(CobolControlFlow.Node::syntaxIndex));
    }

    CobolDataFlow build(StartRuleContext tree) {
        visit(tree);
        return CobolReachingDefinitions.analyze(new CobolDataFlow(symbols.symbols(), operations, limitations), controlFlow);
    }

    @Override public Void visitDataDescriptionEntryFormat1(DataDescriptionEntryFormat1Context ctx) {
        if (!ctx.dataRedefinesClause().isEmpty()) {
            limitations.add(new Limitation("REDEFINES shares storage; indirect alias reads and writes not expanded",
                    source.at(ctx.getStart())));
        }
        return visitChildren(ctx);
    }

    @Override public Void visitDataDescriptionEntryFormat2(DataDescriptionEntryFormat2Context ctx) {
        limitations.add(new Limitation("RENAMES shares storage; indirect alias effects not expanded", source.at(ctx.getStart())));
        return visitChildren(ctx);
    }

    @Override public Void visitStatement(StatementContext ctx) {
        var effect = new Effects(ctx);
        classify(ctx, effect);
        effect.finish();
        return visitChildren(ctx);
    }

    private void classify(StatementContext ctx, Effects e) {
        if (ctx.moveStatement() != null) {
            var move = ctx.moveStatement().moveToStatement();
            if (move != null) e.assign(TransferKind.MOVE, e.values(move.moveToSendingArea()), move.identifier(), false);
            else e.unknown(ctx, "MOVE CORRESPONDING requires field correspondence analysis");
        } else if (ctx.computeStatement() != null) {
            var compute = ctx.computeStatement();
            e.assign(TransferKind.COMPUTE, e.values(compute.arithmeticExpression()),
                    compute.computeStore().stream().map(ComputeStoreContext::identifier).toList(), false);
            e.arithmeticLimit(compute.onSizeErrorPhrase());
        } else if (ctx.addStatement() != null) {
            add(ctx.addStatement(), e);
        } else if (ctx.subtractStatement() != null) {
            subtract(ctx.subtractStatement(), e);
        } else if (ctx.multiplyStatement() != null) {
            multiply(ctx.multiplyStatement(), e);
        } else if (ctx.divideStatement() != null) {
            divide(ctx.divideStatement(), e);
        } else if (ctx.ifStatement() != null) {
            e.values(ctx.ifStatement().condition());
        } else if (ctx.evaluateStatement() != null) {
            var evaluate = ctx.evaluateStatement();
            e.values(evaluate.evaluateSelect());
            evaluate.evaluateAlsoSelect().forEach(e::values);
            evaluate.evaluateWhenPhrase().forEach(when -> when.evaluateWhen().forEach(e::values));
        } else if (ctx.goToStatement() != null) {
            var depending = ctx.goToStatement().goToDependingOnStatement();
            if (depending != null && depending.identifier() != null) e.values(depending.identifier());
        } else if (ctx.performStatement() != null) {
            perform(ctx.performStatement(), e);
        } else if (ctx.initializeStatement() != null) {
            var initialize = ctx.initializeStatement();
            if (initialize.initializeReplacingPhrase() != null) {
                e.unknown(initialize, "INITIALIZE REPLACING requires category-specific field expansion");
            } else e.assign(TransferKind.INITIALIZE, List.of(e.external("type default", initialize)),
                    initialize.identifier(), false);
        } else if (ctx.acceptStatement() != null) {
            var accept = ctx.acceptStatement();
            e.assign(TransferKind.INPUT, List.of(e.external("ACCEPT input", accept)), List.of(accept.identifier()), false);
            e.limit(accept, "ACCEPT input and exception outcome are external");
        } else if (ctx.displayStatement() != null) {
            e.values(ctx.displayStatement());
        } else if (ctx.callStatement() != null) {
            call(ctx.callStatement(), e);
        } else if (ctx.readStatement() != null) {
            var read = ctx.readStatement();
            if (read.readKey() != null) e.values(read.readKey());
            if (read.readInto() != null) e.assign(TransferKind.INPUT, List.of(e.external("READ " + text(read.fileName()), read)),
                    List.of(read.readInto().identifier()), false);
            e.limit(read, "READ record buffer, file status and I/O outcomes are not modeled");
        } else if (ctx.setStatement() != null) {
            set(ctx.setStatement(), e);
        } else if (ctx.continueStatement() != null || ctx.gobackStatement() != null || ctx.exitStatement() != null
                || ctx.nextSentenceStatement() != null) {
            // No explicit data operand.
        } else if (ctx.stopStatement() != null) {
            e.values(ctx.stopStatement());
        } else {
            e.unknown(ctx, "Unmodeled data effects: " + ctx.getStart().getText());
        }
    }

    private void add(AddStatementContext ctx, Effects e) {
        if (ctx.addToStatement() != null) {
            var add = ctx.addToStatement();
            e.assign(TransferKind.COMPUTE, e.values(add.addFrom()), add.addTo().stream().map(AddToContext::identifier).toList(), true);
        } else if (ctx.addToGivingStatement() != null) {
            var add = ctx.addToGivingStatement();
            e.assign(TransferKind.COMPUTE, combine(e.values(add.addFrom()), e.values(add.addToGiving())),
                    add.addGiving().stream().map(AddGivingContext::identifier).toList(), false);
        } else e.unknown(ctx, "ADD CORRESPONDING requires field correspondence analysis");
        e.arithmeticLimit(ctx.onSizeErrorPhrase());
    }

    private void subtract(SubtractStatementContext ctx, Effects e) {
        if (ctx.subtractFromStatement() != null) {
            var subtract = ctx.subtractFromStatement();
            e.assign(TransferKind.COMPUTE, e.values(subtract.subtractSubtrahend()),
                    subtract.subtractMinuend().stream().map(SubtractMinuendContext::identifier).toList(), true);
        } else if (ctx.subtractFromGivingStatement() != null) {
            var subtract = ctx.subtractFromGivingStatement();
            e.assign(TransferKind.COMPUTE, combine(e.values(subtract.subtractSubtrahend()),
                    e.values(subtract.subtractMinuendGiving())),
                    subtract.subtractGiving().stream().map(SubtractGivingContext::identifier).toList(), false);
        } else e.unknown(ctx, "SUBTRACT CORRESPONDING requires field correspondence analysis");
        e.arithmeticLimit(ctx.onSizeErrorPhrase());
    }

    private void multiply(MultiplyStatementContext ctx, Effects e) {
        var factor = ctx.identifier() == null ? e.values(ctx.literal()) : e.values(ctx.identifier());
        if (ctx.multiplyRegular() != null) {
            e.assign(TransferKind.COMPUTE, factor, ctx.multiplyRegular().multiplyRegularOperand().stream()
                    .map(MultiplyRegularOperandContext::identifier).toList(), true);
        } else if (ctx.multiplyGiving() != null) {
            var giving = ctx.multiplyGiving();
            e.assign(TransferKind.COMPUTE, combine(factor, e.values(giving.multiplyGivingOperand())),
                    giving.multiplyGivingResult().stream().map(MultiplyGivingResultContext::identifier).toList(), false);
        }
        e.arithmeticLimit(ctx.onSizeErrorPhrase());
    }

    private void divide(DivideStatementContext ctx, Effects e) {
        var first = ctx.identifier() == null ? e.values(ctx.literal()) : e.values(ctx.identifier());
        if (ctx.divideIntoStatement() != null) {
            var targets = ctx.divideIntoStatement().divideInto().stream().map(DivideIntoContext::identifier).toList();
            e.assign(TransferKind.COMPUTE, first, targets, true);
            if (ctx.divideRemainder() != null) e.unknown(ctx.divideRemainder(), "REMAINDER of in-place DIVIDE not modeled");
        } else {
            var into = ctx.divideIntoGivingStatement();
            var by = ctx.divideByGivingStatement();
            var inputs = combine(first, into != null
                    ? e.values(into.identifier() != null ? into.identifier() : into.literal())
                    : e.values(by.identifier() != null ? by.identifier() : by.literal()));
            var giving = into != null ? into.divideGivingPhrase() : by.divideGivingPhrase();
            if (giving != null) e.assign(TransferKind.COMPUTE, inputs,
                    giving.divideGiving().stream().map(DivideGivingContext::identifier).toList(), false);
            if (ctx.divideRemainder() != null) e.assign(TransferKind.COMPUTE, inputs,
                    List.of(ctx.divideRemainder().identifier()), false);
        }
        e.arithmeticLimit(ctx.onSizeErrorPhrase());
    }

    private void perform(PerformStatementContext ctx, Effects e) {
        var type = ctx.performInlineStatement() != null ? ctx.performInlineStatement().performType()
                : ctx.performProcedureStatement().performType();
        if (type == null) return;
        if (type.performVarying() != null) {
            var clause = type.performVarying().performVaryingClause();
            e.limit(type, "VARYING initialization and increment are not assigned to separate control-flow nodes");
            varying(clause.performVaryingPhrase(), e);
            clause.performAfter().forEach(after -> varying(after.performVaryingPhrase(), e));
            if (!clause.performAfter().isEmpty()) e.limit(type, "Nested iteration ordering not modeled");
        } else {
            if (type.performUntil() != null) e.controlIndex = type.getStart().getTokenIndex();
            e.values(type);
        }
    }

    private void varying(PerformVaryingPhraseContext ctx, Effects e) {
        if (ctx.identifier() == null) {
            e.unknown(ctx, "Unresolved VARYING target");
            return;
        }
        e.assign(TransferKind.ITERATION, e.values(ctx.performFrom()), List.of(ctx.identifier()), false);
        e.assign(TransferKind.ITERATION, e.values(ctx.performBy()), List.of(ctx.identifier()), true);
        e.values(ctx.performUntil());
    }

    private void set(SetStatementContext ctx, Effects e) {
        if (ctx.setUpDownByStatement() != null) {
            var set = ctx.setUpDownByStatement();
            e.assign(TransferKind.COMPUTE, e.values(set.setByValue()), set.setTo().stream().map(SetToContext::identifier).toList(), true);
        } else {
            e.unknown(ctx, "SET condition names, pointers and index semantics are not expanded");
        }
    }

    private void call(CallStatementContext ctx, Effects e) {
        if (ctx.identifier() != null) e.values(ctx.identifier());
        if (ctx.callUsingPhrase() != null) {
            for (var parameter : ctx.callUsingPhrase().callUsingParameter()) {
                if (parameter.callByReferencePhrase() != null) {
                    for (var argument : parameter.callByReferencePhrase().callByReference()) {
                        if (argument.identifier() != null) e.access(argument.identifier(), AccessKind.POSSIBLE_READ_WRITE);
                        else e.limit(argument, "CALL reference argument without resolved data item");
                    }
                } else {
                    e.values(parameter);
                    if (parameter.getText().toUpperCase(java.util.Locale.ROOT).contains("ADDRESSOF")) {
                        e.limit(parameter, "CALL passes an address; indirect writes are unknown");
                    }
                }
            }
        }
        if (ctx.callGivingPhrase() != null) e.assign(TransferKind.RETURN_VALUE, List.of(e.external("CALL return", ctx)),
                List.of(ctx.callGivingPhrase().identifier()), false);
        e.limit(ctx, "External CALL data effects are unknown; BY REFERENCE may read or write");
    }

    private static List<Operand> combine(List<Operand> left, List<Operand> right) {
        var result = new ArrayList<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private final class Effects {
        private final StatementContext statement;
        private int controlIndex;
        private final List<Access> accesses = new ArrayList<>();
        private final List<Transfer> transfers = new ArrayList<>();
        private final java.util.Set<String> noted = new java.util.LinkedHashSet<>();

        Effects(StatementContext statement) {
            this.statement = statement;
            this.controlIndex = statement.getStart().getTokenIndex();
        }

        List<Operand> values(List<? extends ParserRuleContext> contexts) {
            return contexts.stream().flatMap(ctx -> values(ctx).stream()).toList();
        }

        List<Operand> values(ParserRuleContext ctx) {
            if (ctx == null) return List.of();
            var result = new ArrayList<Operand>();
            collectValues(ctx, result);
            return result;
        }

        private void collectValues(ParseTree tree, List<Operand> output) {
            if (tree instanceof StatementContext) return;
            if (tree instanceof IdentifierContext || tree instanceof QualifiedDataNameContext
                    || tree instanceof ConditionNameReferenceContext) {
                output.add(access((ParserRuleContext) tree, AccessKind.READ));
                return;
            }
            if (tree instanceof LiteralContext literal) {
                output.add(new Operand(OperandKind.LITERAL, text(literal), "", span(literal)));
                return;
            }
            for (int i = 0; i < tree.getChildCount(); i++) collectValues(tree.getChild(i), output);
        }

        Operand access(ParserRuleContext ctx, AccessKind kind) {
            var resolved = symbols.resolve(ctx);
            Operand operand;
            if (resolved.isPresent()) {
                var symbol = resolved.get();
                operand = new Operand(OperandKind.SYMBOL, text(ctx), symbol.id(), span(ctx));
                if (symbol.complexStorage()) limit(ctx, "Group, condition or aliased storage not expanded: " + symbol.qualifiedName());
            } else {
                operand = new Operand(OperandKind.UNKNOWN, text(ctx), "", span(ctx));
                limit(ctx, "Unresolved or ambiguous operand: " + text(ctx));
            }
            accesses.add(new Access(kind, operand));
            if (ctx instanceof IdentifierContext identifier && identifier.tableCall() != null) {
                var table = identifier.tableCall();
                table.subscript().forEach(this::addressReads);
                if (table.referenceModifier() != null) addressReads(table.referenceModifier());
                limit(ctx, "Table occurrence or reference modifier not resolved to a distinct storage location");
            }
            return operand;
        }

        private void addressReads(ParseTree tree) {
            if (tree instanceof IdentifierContext || tree instanceof QualifiedDataNameContext) {
                access((ParserRuleContext) tree, AccessKind.ADDRESS_READ);
                return;
            }
            for (int i = 0; i < tree.getChildCount(); i++) addressReads(tree.getChild(i));
        }

        void assign(TransferKind kind, List<Operand> inputs, List<IdentifierContext> destinations, boolean inPlace) {
            for (var target : destinations) {
                Operand destination = access(target, inPlace ? AccessKind.READ_WRITE : AccessKind.WRITE);
                List<Operand> sources = inPlace ? combine(inputs, List.of(destination)) : inputs;
                if (sources.isEmpty()) {
                    sources = List.of(new Operand(OperandKind.UNKNOWN, "implicit value", "", span(target)));
                    limit(target, "Implicit value not resolved");
                }
                transfers.add(new Transfer(kind, sources, destination));
            }
        }

        Operand external(String name, ParserRuleContext context) {
            return new Operand(OperandKind.EXTERNAL, name, "", span(context));
        }

        void unknown(ParserRuleContext ctx, String message) {
            limit(ctx, message);
            var operands = new ArrayList<Operand>();
            collectUnknown(ctx, operands);
            operands.forEach(operand -> accesses.add(new Access(AccessKind.UNKNOWN, operand)));
        }

        private void collectUnknown(ParseTree tree, List<Operand> output) {
            if (tree instanceof StatementContext && tree != statement) return;
            if (tree instanceof IdentifierContext || tree instanceof QualifiedDataNameContext
                    || tree instanceof ConditionNameReferenceContext) {
                var ctx = (ParserRuleContext) tree;
                var symbol = symbols.resolve(ctx);
                output.add(new Operand(symbol.isPresent() ? OperandKind.SYMBOL : OperandKind.UNKNOWN,
                        text(ctx), symbol.map(Symbol::id).orElse(""), span(ctx)));
                return;
            }
            for (int i = 0; i < tree.getChildCount(); i++) collectUnknown(tree.getChild(i), output);
        }

        void arithmeticLimit(ParserRuleContext exception) {
            if (exception != null) limit(exception, "Size-error branch may prevent a write");
        }

        void limit(ParserRuleContext ctx, String message) {
            if (noted.add(message)) limitations.add(new Limitation(message, source.at(ctx.getStart())));
        }

        void finish() {
            if (accesses.isEmpty() && transfers.isEmpty() && noted.isEmpty()) return;
            var candidates = controlNodes.getOrDefault(controlIndex, List.of());
            String control = candidates.size() == 1 ? candidates.getFirst().id() : "";
            operations.add(new Operation(CobolDataSymbols.program(CobolDataSymbols.unit(statement)), control,
                    statement.getStart().getText(), span(statement), accesses, transfers, !noted.isEmpty()));
        }
    }

    private Span span(ParserRuleContext ctx) { return source.span(ctx.getStart(), ctx.getStop()); }
    private String text(ParserRuleContext ctx) { return tokens.getText(ctx.getSourceInterval()).strip(); }
}
