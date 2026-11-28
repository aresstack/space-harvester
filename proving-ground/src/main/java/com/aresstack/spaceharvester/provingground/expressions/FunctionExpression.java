package com.aresstack.spaceharvester.provingground.expressions;

import java.util.ArrayList;
import java.util.List;

/**
 * A function call like {@code {{otp()}}} or {@code {{wrap('x';{{userName}})}}} (from WD4J): the arguments (literals,
 * variables, nested calls) are resolved first, then the function the context offers under this name is called. Lazy, so
 * a one-time code is computed at the moment the step needs it. Unlike WD4J the functions come from the context, not
 * from a global registry.
 */
public final class FunctionExpression implements ResolvableExpression {

    private final String functionName;
    private final List<ResolvableExpression> arguments;

    public FunctionExpression(String functionName, List<ResolvableExpression> arguments) {
        this.functionName = functionName;
        this.arguments = List.copyOf(arguments);
    }

    @Override
    public Object resolve(ResolutionContext context) throws ExpressionException {
        ExpressionFunction function = context.functions().find(functionName);
        if (function == null) {
            throw new UnresolvedSymbolException(functionName + "()");
        }
        List<String> resolved = new ArrayList<>();
        for (ResolvableExpression argument : arguments) {
            Object value = argument.resolve(context);
            resolved.add(value == null ? "" : value.toString());
        }
        return function.invoke(resolved, context);
    }

    @Override
    public boolean isResolved(ResolutionContext context) {
        return arguments.stream().allMatch(argument -> argument.isResolved(context));
    }

    public String getFunctionName() {
        return functionName;
    }

    public List<ResolvableExpression> getArguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "Function(" + functionName + ")";
    }
}
