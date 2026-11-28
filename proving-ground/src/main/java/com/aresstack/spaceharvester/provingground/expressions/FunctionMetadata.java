package com.aresstack.spaceharvester.provingground.expressions;

import java.util.List;

/**
 * Name and help of a function, for the editor's completion (from WD4J).
 *
 * @param name                  the name the function is called by (case does not matter)
 * @param description           what it does
 * @param parameterNames        its parameters; a trailing "?" marks an optional one
 * @param parameterDescriptions one line per parameter, or empty
 */
public record FunctionMetadata(String name, String description, List<String> parameterNames,
                               List<String> parameterDescriptions) {

    public FunctionMetadata {
        parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
        parameterDescriptions = parameterDescriptions == null ? List.of() : List.copyOf(parameterDescriptions);
    }
}
