package com.aresstack.spaceharvester.provingground.expressions;

/** A function was called but failed (from WD4J). */
public class FunctionExecutionException extends ExpressionException {

    public FunctionExecutionException(String message) {
        super(message);
    }

    public FunctionExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
