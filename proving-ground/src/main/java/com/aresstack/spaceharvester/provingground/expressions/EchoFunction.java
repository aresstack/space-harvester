package com.aresstack.spaceharvester.provingground.expressions;

import java.util.List;

/** {@code {{Echo(a;b)}}}: the arguments joined with a space (WD4J built-in). */
public final class EchoFunction implements ExpressionFunction {

    private static final FunctionMetadata META = new FunctionMetadata("Echo",
            "Gibt die Argumente mit Leerzeichen verbunden zurück", List.of("value", "more..."), null);

    @Override
    public String invoke(List<String> args, ResolutionContext context) {
        return String.join(" ", args);
    }

    @Override
    public FunctionMetadata metadata() {
        return META;
    }
}
