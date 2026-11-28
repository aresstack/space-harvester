package com.aresstack.spaceharvester.provingground.expressions;

/** Plain text (from WD4J). */
public final class LiteralExpression implements ResolvableExpression {

    private final String literal;

    public LiteralExpression(String literal) {
        this.literal = literal != null ? literal : "";
    }

    @Override
    public Object resolve(ResolutionContext context) {
        return literal;
    }

    @Override
    public boolean isResolved(ResolutionContext context) {
        return true;
    }

    public String getText() {
        return literal;
    }

    @Override
    public String toString() {
        return literal;
    }
}
