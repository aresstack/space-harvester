package com.aresstack.spaceharvester.provingground.expressions;

import java.util.List;

/**
 * A function callable as {@code {{name(arg1;arg2)}}} (from WD4J). It gets the resolved arguments and the context (for
 * variables such as the current user, and services such as the page under test).
 */
public interface ExpressionFunction {

    String invoke(List<String> args, ResolutionContext context) throws FunctionExecutionException;

    FunctionMetadata metadata();
}
