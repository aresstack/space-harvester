package com.aresstack.spaceharvester.mainframe.cobol.internal;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.*;
import io.proleap.cobol.CobolBaseVisitor;
import io.proleap.cobol.CobolParser.*;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.data.datadescription.DataDescriptionEntry;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class CobolStructureVisitor extends CobolBaseVisitor<Void> {
    private record DataParent(int level, int index) { }

    private final MappedCobolText source;
    private final CommonTokenStream tokens;
    private final Program program;
    private final List<Element> elements = new ArrayList<>();
    private final List<ProgramCall> calls = new ArrayList<>();
    private final List<SymbolReference> references = new ArrayList<>();
    private final Deque<Integer> scopes = new ArrayDeque<>();
    private final Deque<DataParent> dataParents = new ArrayDeque<>();
    private int lastDataItem = -1;

    CobolStructureVisitor(MappedCobolText source, CommonTokenStream tokens, Program program) {
        this.source = source;
        this.tokens = tokens;
        this.program = program;
    }

    List<Element> elements() { return elements; }
    List<ProgramCall> calls() { return calls; }
    List<SymbolReference> references() { return references; }

    @Override
    public Void visitIdentificationDivision(IdentificationDivisionContext ctx) {
        return scope(Kind.DIVISION, "IDENTIFICATION DIVISION", ctx);
    }

    @Override
    public Void visitEnvironmentDivision(EnvironmentDivisionContext ctx) {
        return scope(Kind.DIVISION, "ENVIRONMENT DIVISION", ctx);
    }

    @Override
    public Void visitDataDivision(DataDivisionContext ctx) {
        return scope(Kind.DIVISION, "DATA DIVISION", ctx);
    }

    @Override
    public Void visitProcedureDivision(ProcedureDivisionContext ctx) {
        return scope(Kind.PROCEDURE_DIVISION, "PROCEDURE DIVISION", ctx);
    }

    @Override
    public Void visitProgramIdParagraph(ProgramIdParagraphContext ctx) {
        if (ctx.programName() != null) {
            add(Kind.PROGRAM_ID, SourceMappedDocumentListener.unquote(ctx.programName().getText()),
                    parent(), ctx, Map.of());
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitConfigurationSection(ConfigurationSectionContext ctx) {
        return section(Kind.SECTION, "CONFIGURATION", ctx);
    }

    @Override
    public Void visitInputOutputSection(InputOutputSectionContext ctx) {
        return section(Kind.SECTION, "INPUT-OUTPUT", ctx);
    }

    @Override
    public Void visitWorkingStorageSection(WorkingStorageSectionContext ctx) {
        return section(Kind.WORKING_STORAGE, "WORKING-STORAGE", ctx);
    }

    @Override
    public Void visitLocalStorageSection(LocalStorageSectionContext ctx) {
        return section(Kind.SECTION, "LOCAL-STORAGE", ctx);
    }

    @Override
    public Void visitFileSection(FileSectionContext ctx) {
        return section(Kind.FILE_SECTION, "FILE", ctx);
    }

    @Override
    public Void visitLinkageSection(LinkageSectionContext ctx) {
        return section(Kind.LINKAGE_SECTION, "LINKAGE", ctx);
    }

    @Override
    public Void visitScreenSection(ScreenSectionContext ctx) {
        return section(Kind.SCREEN_SECTION, "SCREEN", ctx);
    }

    @Override
    public Void visitReportSection(ReportSectionContext ctx) {
        return section(Kind.SECTION, "REPORT", ctx);
    }

    @Override
    public Void visitCommunicationSection(CommunicationSectionContext ctx) {
        return section(Kind.SECTION, "COMMUNICATION", ctx);
    }

    @Override
    public Void visitDataBaseSection(DataBaseSectionContext ctx) {
        return section(Kind.SECTION, "DATABASE", ctx);
    }

    @Override
    public Void visitProgramLibrarySection(ProgramLibrarySectionContext ctx) {
        return section(Kind.SECTION, "PROGRAM-LIBRARY", ctx);
    }

    @Override
    public Void visitProcedureSection(ProcedureSectionContext ctx) {
        return ctx.procedureSectionHeader() == null || ctx.procedureSectionHeader().sectionName() == null
                ? visitChildren(ctx)
                : section(Kind.SECTION, ctx.procedureSectionHeader().sectionName().getText(), ctx);
    }

    @Override
    public Void visitProcedureDeclarative(ProcedureDeclarativeContext ctx) {
        return ctx.procedureSectionHeader() == null || ctx.procedureSectionHeader().sectionName() == null
                ? visitChildren(ctx)
                : section(Kind.SECTION, ctx.procedureSectionHeader().sectionName().getText(), ctx);
    }

    @Override
    public Void visitParagraph(ParagraphContext ctx) {
        return ctx.paragraphName() == null ? visitChildren(ctx)
                : scope(Kind.PARAGRAPH, ctx.paragraphName().getText(), ctx);
    }

    @Override
    public Void visitFileDescriptionEntry(FileDescriptionEntryContext ctx) {
        return ctx.fileName() == null ? visitChildren(ctx)
                : scope(Kind.FILE_DESCRIPTOR, ctx.fileName().getText(), ctx);
    }

    private Void section(Kind kind, String name, ParserRuleContext ctx) {
        return scope(kind, name + " SECTION", ctx);
    }

    private Void scope(Kind kind, String name, ParserRuleContext ctx) {
        int index = add(kind, name, parent(), ctx, Map.of());
        scopes.push(index);
        dataParents.clear();
        lastDataItem = -1;
        try {
            return visitChildren(ctx);
        } finally {
            scopes.pop();
            dataParents.clear();
            lastDataItem = -1;
        }
    }

    @Override
    public Void visitDataDescriptionEntryFormat1(DataDescriptionEntryFormat1Context ctx) {
        String name = ctx.dataName() == null ? "FILLER" : ctx.dataName().getText();
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!ctx.dataPictureClause().isEmpty() && ctx.dataPictureClause(0).pictureString() != null) {
            attributes.put("PIC", ctx.dataPictureClause(0).pictureString().getText());
        }
        if (!ctx.dataValueClause().isEmpty()) {
            attributes.put("VALUE", values(ctx.dataValueClause(0)));
        }
        if (!ctx.dataRedefinesClause().isEmpty() && ctx.dataRedefinesClause(0).dataName() != null) {
            attributes.put("REDEFINES", ctx.dataRedefinesClause(0).dataName().getText());
        }
        if (!ctx.dataOccursClause().isEmpty()) {
            attributes.put("OCCURS", text(ctx.dataOccursClause(0)));
        }
        String levelText = ctx.getStart().getText();
        try {
            addData(Integer.parseInt(levelText), name, ctx, attributes);
        } catch (NumberFormatException ignored) {
            // Skip an incomplete declaration instead of inventing a level number.
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitDataDescriptionEntryFormat2(DataDescriptionEntryFormat2Context ctx) {
        if (ctx.dataName() != null) {
            Map<String, String> attributes = ctx.dataRenamesClause() == null ? Map.of()
                    : Map.of("RENAMES", text(ctx.dataRenamesClause()));
            addData(66, ctx.dataName().getText(), ctx, attributes);
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitDataDescriptionEntryFormat3(DataDescriptionEntryFormat3Context ctx) {
        if (ctx.conditionName() != null) {
            Map<String, String> attributes = ctx.dataValueClause() == null ? Map.of()
                    : Map.of("VALUE", values(ctx.dataValueClause()));
            addData(88, ctx.conditionName().getText(), ctx, attributes);
        }
        return visitChildren(ctx);
    }

    private String values(DataValueClauseContext ctx) {
        return ctx.dataValueInterval().stream().map(this::text).collect(Collectors.joining(", "));
    }

    private void addData(int level, String name, ParserRuleContext ctx, Map<String, String> parameters) {
        Map<String, String> attributes = new LinkedHashMap<>(parameters);
        attributes.put("LEVEL", Integer.toString(level));
        Kind kind = switch (level) {
            case 1 -> Kind.LEVEL_01;
            case 77 -> Kind.LEVEL_77;
            case 88 -> Kind.LEVEL_88;
            default -> Kind.DATA_ITEM;
        };
        int dataParent = findDataParent(level);
        int index = add(kind, name, dataParent, ctx, attributes);
        if (level != 88 && level != 66) {
            lastDataItem = index;
            if (level != 77) {
                dataParents.push(new DataParent(level, index));
            }
        }
        addReferences(ctx);
    }

    private int findDataParent(int level) {
        if (level == 88) {
            return lastDataItem < 0 ? parent() : lastDataItem;
        }
        if (level == 66) {
            return dataParents.stream().filter(entry -> entry.level() == 1)
                    .mapToInt(DataParent::index).findFirst().orElse(parent());
        }
        if (level == 1 || level == 77) {
            dataParents.clear();
        }
        while (!dataParents.isEmpty() && dataParents.peek().level() >= level) {
            dataParents.pop();
        }
        return dataParents.isEmpty() ? parent() : dataParents.peek().index();
    }

    private void addReferences(ParserRuleContext ctx) {
        if (program == null) {
            return;
        }
        if (program.getASGElementRegistry().getASGElement(ctx) instanceof DataDescriptionEntry entry) {
            for (var call : entry.getCalls()) {
                if (call.getCtx() != null) {
                    references.add(new SymbolReference(entry.getName(), span(call.getCtx()), span(ctx)));
                }
            }
        }
    }

    @Override
    public Void visitCallStatement(CallStatementContext ctx) {
        if (ctx.literal() != null || ctx.identifier() != null) {
            boolean literal = ctx.literal() != null && ctx.literal().NONNUMERICLITERAL() != null;
            String target = literal ? SourceMappedDocumentListener.unquote(ctx.literal().getText())
                    : text(ctx.identifier() != null ? ctx.identifier() : ctx.literal());
            TargetKind kind = literal ? TargetKind.LITERAL : TargetKind.DYNAMIC;
            calls.add(new ProgramCall(kind, target, span(ctx), text(ctx)));
            add(Kind.CALL_STMT, target, parent(), ctx, Map.of("TARGET", target, "TARGET_KIND", kind.name()));
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitPerformStatement(PerformStatementContext ctx) {
        var procedure = ctx.performProcedureStatement();
        if (procedure != null && !procedure.procedureName().isEmpty()) {
            String target = text(procedure.procedureName(0));
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("TARGET", target);
            if (procedure.procedureName().size() > 1) {
                attributes.put("THRU", text(procedure.procedureName(1)));
            }
            add(Kind.PERFORM_STMT, target, parent(), ctx, attributes);
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitExecSqlStatement(ExecSqlStatementContext ctx) {
        add(Kind.EXEC_SQL, "EXEC SQL", parent(), ctx, Map.of());
        return visitChildren(ctx);
    }

    @Override
    public Void visitExecSqlImsStatement(ExecSqlImsStatementContext ctx) {
        add(Kind.EXEC_SQLIMS, "EXEC SQLIMS", parent(), ctx, Map.of());
        return visitChildren(ctx);
    }

    @Override
    public Void visitExecCicsStatement(ExecCicsStatementContext ctx) {
        add(Kind.EXEC_CICS, "EXEC CICS", parent(), ctx, Map.of());
        return visitChildren(ctx);
    }

    private int parent() {
        return scopes.isEmpty() ? -1 : scopes.peek();
    }

    private int add(Kind kind, String name, int parentIndex, ParserRuleContext ctx, Map<String, String> attributes) {
        // Use the header location for navigation; avoid copying the entire body of a large scope.
        String raw = switch (kind) {
            case DIVISION, PROCEDURE_DIVISION, SECTION, WORKING_STORAGE, LINKAGE_SECTION,
                    FILE_SECTION, SCREEN_SECTION, PARAGRAPH, FILE_DESCRIPTOR -> name;
            case EXEC_SQL, EXEC_SQLIMS, EXEC_CICS -> embeddedText(ctx);
            default -> text(ctx);
        };
        elements.add(new Element(kind, name, parentIndex, span(ctx), raw, attributes));
        return elements.size() - 1;
    }

    private String embeddedText(ParserRuleContext ctx) {
        return text(ctx).replaceAll("(?m)^\\s*\\*>EXEC(?:SQLIMS|SQL|CICS)[ \\t]*", "")
                .replaceAll("(?i)(END-EXEC)[ \\t]*\\}", "$1").strip();
    }

    private Span span(ParserRuleContext ctx) {
        return source.span(ctx.getStart(), ctx.getStop());
    }

    private String text(ParserRuleContext ctx) {
        return ctx == null ? "" : tokens.getText(ctx.getSourceInterval()).strip();
    }
}
