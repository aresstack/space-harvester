package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow.Symbol;
import io.proleap.cobol.CobolBaseVisitor;
import io.proleap.cobol.CobolParser.*;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.call.DataDescriptionEntryCall;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntryGroup;
import org.antlr.v4.runtime.ParserRuleContext;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Verify ASG references against declarations and reject ambiguous or cross-program bindings. */
final class CobolDataSymbols extends CobolBaseVisitor<Void> {
    private record Declaration(DataDescriptionEntry entry, ProgramUnitContext unit, Symbol symbol, List<String> parents) { }
    private final MappedCobolText source;
    private final Program semantic;
    private final Map<DataDescriptionEntry, Declaration> declarations = new IdentityHashMap<>();
    private final Map<ProgramUnitContext, Map<String, List<Declaration>>> names = new IdentityHashMap<>();
    private final List<Symbol> symbols = new ArrayList<>();

    CobolDataSymbols(MappedCobolText source, Program semantic, StartRuleContext tree) {
        this.source = source;
        this.semantic = semantic;
        if (semantic != null) visit(tree);
    }

    List<Symbol> symbols() { return List.copyOf(symbols); }

    @Override public Void visitDataDescriptionEntryFormat1(DataDescriptionEntryFormat1Context ctx) {
        register(ctx);
        return visitChildren(ctx);
    }

    @Override public Void visitDataDescriptionEntryFormat2(DataDescriptionEntryFormat2Context ctx) {
        register(ctx);
        return visitChildren(ctx);
    }

    @Override public Void visitDataDescriptionEntryFormat3(DataDescriptionEntryFormat3Context ctx) {
        register(ctx);
        return visitChildren(ctx);
    }

    private void register(ParserRuleContext ctx) {
        if (!(semantic.getASGElementRegistry().getASGElement(ctx) instanceof DataDescriptionEntry entry)
                || entry.getName() == null || declarations.containsKey(entry)) return;
        var unit = unit(ctx);
        if (unit == null) return;
        var parents = new ArrayList<String>();
        boolean complex = complex(entry);
        for (var parent = entry.getParentDataDescriptionEntryGroup(); parent != null;
             parent = parent.getParentDataDescriptionEntryGroup()) {
            parents.add(normalize(parent.getName()));
            complex |= complex(parent) && hasStorageClause(parent);
        }
        String qualified = entry.getName() + (parents.isEmpty() ? "" : " OF " + String.join(" OF ", parents));
        var symbol = new Symbol("d" + ctx.getStart().getTokenIndex(), program(unit), entry.getName(), qualified,
                source.span(ctx.getStart(), ctx.getStop()), complex);
        var declaration = new Declaration(entry, unit, symbol, List.copyOf(parents));
        declarations.put(entry, declaration);
        names.computeIfAbsent(unit, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(normalize(entry.getName()), ignored -> new ArrayList<>()).add(declaration);
        symbols.add(symbol);
    }

    private static boolean complex(DataDescriptionEntry entry) {
        return entry.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.CONDITION
                || entry.getDataDescriptionEntryType() == DataDescriptionEntry.DataDescriptionEntryType.RENAME
                || (entry instanceof DataDescriptionEntryGroup group && !group.getDataDescriptionEntries().isEmpty())
                || hasStorageClause(entry);
    }

    private static boolean hasStorageClause(DataDescriptionEntry entry) {
        return entry.getCtx() instanceof DataDescriptionEntryFormat1Context ctx
                && (!ctx.dataRedefinesClause().isEmpty() || !ctx.dataOccursClause().isEmpty());
    }

    Optional<Symbol> resolve(ParserRuleContext ctx) {
        if (semantic == null) return Optional.empty();
        var element = semantic.getASGElementRegistry().getASGElement(ctx);
        if (!(element instanceof Call call) || !(call.unwrap() instanceof DataDescriptionEntryCall data)) {
            return Optional.empty();
        }
        var selected = declarations.get(data.getDataDescriptionEntry());
        if (selected == null || selected.unit() != unit(ctx)) return Optional.empty();
        List<String> qualifiers = qualifiers(ctx);
        if (qualifiers == null) return Optional.empty();
        var candidates = names.getOrDefault(selected.unit(), Map.of())
                .getOrDefault(normalize(selected.symbol().name()), List.of()).stream()
                .filter(candidate -> matches(candidate.parents(), qualifiers)).toList();
        return candidates.size() == 1 && candidates.getFirst() == selected
                ? Optional.of(selected.symbol()) : Optional.empty();
    }

    private static List<String> qualifiers(ParserRuleContext ctx) {
        if (ctx instanceof IdentifierContext identifier) {
            if (identifier.qualifiedDataName() != null) return qualifiers(identifier.qualifiedDataName());
            if (identifier.tableCall() != null) return qualifiers(identifier.tableCall().qualifiedDataName());
            return null;
        }
        if (ctx instanceof QualifiedDataNameContext name) {
            var format = name.qualifiedDataNameFormat1();
            if (format == null || format.inFile() != null) return null;
            var result = new ArrayList<String>();
            for (var qualifier : format.qualifiedInData()) {
                if (qualifier.inData() == null) return null;
                result.add(normalize(qualifier.inData().dataName().getText()));
            }
            return result;
        }
        if (ctx instanceof ConditionNameReferenceContext condition) {
            if (condition.inFile() != null || !condition.inMnemonic().isEmpty()) return null;
            return condition.inData().stream().map(item -> normalize(item.dataName().getText())).toList();
        }
        return null;
    }

    private static boolean matches(List<String> parents, List<String> qualifiers) {
        int position = 0;
        for (String parent : parents) {
            if (position < qualifiers.size() && parent.equals(qualifiers.get(position))) position++;
        }
        return position == qualifiers.size();
    }

    static ProgramUnitContext unit(ParserRuleContext ctx) {
        for (ParserRuleContext current = ctx; current != null; current = current.getParent()) {
            if (current instanceof ProgramUnitContext unit) return unit;
        }
        return null;
    }

    static String program(ProgramUnitContext unit) {
        if (unit == null) return "";
        return unit.identificationDivision().programIdParagraph().programName().getText()
                + "@" + unit.getStart().getTokenIndex();
    }

    private static String normalize(String value) { return value == null ? "" : value.toUpperCase(Locale.ROOT); }
}
