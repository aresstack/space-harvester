package com.aresstack.spaceharvester.provingground.expressions;

import java.util.List;

/** Text and expressions in a row, joined into one string (from WD4J). */
public final class CompositeExpression implements ResolvableExpression {

    private final List<ResolvableExpression> parts;

    public CompositeExpression(List<ResolvableExpression> parts) {
        this.parts = List.copyOf(parts);
    }

    @Override
    public Object resolve(ResolutionContext context) throws ExpressionException {
        StringBuilder text = new StringBuilder();
        for (ResolvableExpression part : parts) {
            Object value = part.resolve(context);
            if (value != null) {
                text.append(value);
            }
        }
        return text.toString();
    }

    @Override
    public boolean isResolved(ResolutionContext context) {
        return parts.stream().allMatch(part -> part.isResolved(context));
    }

    public List<ResolvableExpression> getParts() {
        return parts;
    }

    @Override
    public String toString() {
        return "CompositeExpression(" + parts.size() + " parts)";
    }
}
