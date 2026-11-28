package com.aresstack.spaceharvester.provingground.expressions;

/** A variable reference like {@code {{userName}}} (from WD4J): asks the context for the value. */
public final class VariableExpression implements ResolvableExpression {

    private final String name;

    public VariableExpression(String name) {
        this.name = name;
    }

    @Override
    public Object resolve(ResolutionContext context) throws ExpressionException {
        return context.resolveVariable(name);
    }

    @Override
    public boolean isResolved(ResolutionContext context) {
        return context.isAvailable(name);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "{{" + name + "}}";
    }
}
