package com.aresstack.spaceharvester.provingground.expressions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The functions an expression may call, by name regardless of case (WD4J's {@code BuiltinFunctionProvider} and
 * registry, without the global singleton). Immutable; {@link #with} answers an extended copy.
 */
public final class FunctionRegistry {

    private static final FunctionRegistry BUILTINS = new FunctionRegistry(Map.of()).with(
            new EchoFunction(), new DateFunction());

    private final Map<String, ExpressionFunction> byName;

    private FunctionRegistry(Map<String, ExpressionFunction> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    /** WD4J's built-in functions: {@code Echo}, {@code Date}. */
    public static FunctionRegistry builtins() {
        return BUILTINS;
    }

    /** This registry plus {@code functions} (a function of the same name replaces the earlier one). */
    public FunctionRegistry with(ExpressionFunction... functions) {
        Map<String, ExpressionFunction> copy = new LinkedHashMap<>(byName);
        for (ExpressionFunction function : functions) {
            copy.put(key(function.metadata().name()), function);
        }
        return new FunctionRegistry(copy);
    }

    /** The function of this name, or null. */
    public ExpressionFunction find(String name) {
        return name == null ? null : byName.get(key(name));
    }

    public Collection<ExpressionFunction> functions() {
        return byName.values();
    }

    /** Names and help, for completion in the editor. */
    public List<FunctionMetadata> metadata() {
        List<FunctionMetadata> metadata = new ArrayList<>();
        byName.values().forEach(function -> metadata.add(function.metadata()));
        return metadata;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
