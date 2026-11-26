package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Element;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis.Position;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSource;
import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;

import java.util.ArrayList;
import java.util.List;

public final class CobolOutlineProjection {
    public JclOutlineModel project(CobolSource source, CobolAnalysis analysis) {
        JclOutlineModel model = new JclOutlineModel();
        model.setSourceName(source.name());
        model.setLanguage(JclOutlineModel.Language.COBOL);
        model.setTotalLines((int) source.content().lines().count());
        model.setAnalysisDiagnostics(analysis.diagnostics().stream().map(CobolAnalysis.Diagnostic::message).toList());

        List<JclElement> projected = new ArrayList<>();
        for (Element element : analysis.elements()) {
            int start = line(source, element.span().start());
            int end = line(source, element.span().end());
            JclElementType type = type(element);
            JclElement item = new JclElement(type, element.name(), start, end >= start ? end : start, element.text());
            element.attributes().forEach(item::addParameter);
            if (!element.span().start().source().equals(source.name())) {
                item.addParameter("SOURCE", element.span().start().source());
                item.addParameter("SOURCE_LINE", Integer.toString(element.span().start().line()));
            }
            if (element.parentIndex() >= 0) {
                projected.get(element.parentIndex()).addChild(item);
            }
            projected.add(item);
        }
        // Preserve the expanded syntax-tree order. Physical source lines across copybooks are not comparable.
        projected.forEach(model::addElement);
        return model;
    }

    private int line(CobolSource source, Position position) {
        return source.name().equals(position.source()) ? position.line() : 0;
    }

    private JclElementType type(Element element) {
        if (element.kind() == CobolAnalysis.Kind.CALL_STMT) {
            if (element.span().start().debuggingLine()) {
                return JclElementType.DEBUG_CALL_STMT;
            }
            if (CobolAnalysis.TargetKind.DYNAMIC.name().equals(element.attributes().get("TARGET_KIND"))) {
                return JclElementType.DYNAMIC_CALL_STMT;
            }
        }
        return JclElementType.valueOf(element.kind().name());
    }
}
