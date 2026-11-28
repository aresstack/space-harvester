package com.aresstack.spaceharvester.provingground.expressions;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Variable values from a map (from WD4J). A {@link Supplier} value is asked on every use, for values that change (a
 * one-time code, a password read from the vault only when needed).
 */
public final class MapResolutionContext implements ResolutionContext {

    private final Map<String, ?> values;
    private final FunctionRegistry functions;

    public MapResolutionContext(Map<String, ?> values) {
        this(values, FunctionRegistry.builtins());
    }

    public MapResolutionContext(Map<String, ?> values, FunctionRegistry functions) {
        this.values = values;
        this.functions = functions;
    }

    @Override
    public boolean isAvailable(String name) {
        return values.containsKey(name);
    }

    @Override
    public Object resolveVariable(String name) throws ExpressionException {
        if (!values.containsKey(name)) {
            throw new UnresolvedSymbolException(name);
        }
        Object raw = values.get(name);
        return raw instanceof Supplier<?> supplier ? supplier.get() : raw;
    }

    @Override
    public FunctionRegistry functions() {
        return functions;
    }
}
