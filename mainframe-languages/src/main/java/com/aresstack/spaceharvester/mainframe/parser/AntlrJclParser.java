package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Preserve the editor API while sharing the logical-card parser with stored JCL analyses. */
public class AntlrJclParser {
    private static final Set<String> OUTLINE = Set.of("JOB", "EXEC", "DD", "PROC", "PEND", "SET",
            "INCLUDE", "JCLLIB", "IF", "ELSE", "ENDIF", "OUTPUT");

    public JclOutlineModel parse(String content, String sourceName) {
        var outline = new JclOutlineModel();
        outline.setSourceName(sourceName);
        if (content == null || content.isEmpty()) return outline;
        String[] lines = content.split("\\r?\\n");
        outline.setTotalLines(lines.length);
        Map<Integer, JclElement> elements = new HashMap<>();
        var analysis = new JclAnalyzer().analyze(sourceName, content);
        for (var statement : analysis.statements()) {
            if (!OUTLINE.contains(statement.operation())) continue;
            var type = JclElementType.valueOf(statement.operation());
            String name = type == JclElementType.IF ? statement.operands() : statement.name();
            String raw = String.join("\n", java.util.Arrays.copyOfRange(lines, statement.line() - 1,
                    Math.min(lines.length, statement.endLine())));
            var element = new JclElement(type, name, statement.line(), statement.endLine(), raw);
            if (statement.complete()) statement.parameters().forEach((key, value) ->
                    element.addParameter(key, JclStatements.unquote(value)));
            if (statement.complete() && type == JclElementType.EXEC && !statement.parameters().containsKey("PGM")
                    && !statement.parameters().containsKey("PROC") && !statement.positional().isEmpty()) {
                element.addParameter("PROC", JclStatements.unquote(statement.positional().getFirst()));
            }
            outline.addElement(element);
            int parent = switch (type) {
                case EXEC -> statement.procedureLine() != 0 ? statement.procedureLine() : statement.jobLine();
                case DD -> statement.stepLine() != 0 ? statement.stepLine() : statement.jobLine();
                case PROC -> statement.jobLine();
                case PEND -> statement.procedureLine();
                default -> 0;
            };
            var owner = elements.get(parent);
            if (owner != null) owner.addChild(element);
            elements.put(statement.line(), element);
        }
        return outline;
    }
}

