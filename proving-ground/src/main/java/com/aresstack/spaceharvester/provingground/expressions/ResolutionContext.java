package com.aresstack.spaceharvester.provingground.expressions;

/**
 * Provides variable values and functions at run time (from WD4J; the functions moved here from WD4J's global
 * registry). Lets expressions stay independent of where values come from (a map, the scopes of a test run, KeePass).
 */
public interface ResolutionContext {

    /** Whether the variable is available. */
    boolean isAvailable(String name);

    /** The value of the variable; fails with its name if it is not available. */
    Object resolveVariable(String name) throws ExpressionException;

    /** The functions an expression may call. */
    default FunctionRegistry functions() {
        return FunctionRegistry.builtins();
    }

    /** A service for functions (WD4J's {@code FunctionContext.services()}), e.g. the page under test; or null. */
    default <T> T service(Class<T> type) {
        return null;
    }
}
