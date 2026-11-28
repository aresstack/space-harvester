package com.aresstack.spaceharvester.provingground.expressions;

/** A parsed template or part of it (from WD4J). Resolving is lazy: nothing is computed before {@link #resolve}. */
public interface ResolvableExpression {

    Object resolve(ResolutionContext context) throws ExpressionException;

    /** Whether every variable it refers to is available (functions are not called). */
    boolean isResolved(ResolutionContext context);
}
