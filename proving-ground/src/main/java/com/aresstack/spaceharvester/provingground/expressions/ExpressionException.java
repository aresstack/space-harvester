package com.aresstack.spaceharvester.provingground.expressions;

/** An expression could not be resolved: a variable is missing or a function failed. */
public class ExpressionException extends Exception {

    public ExpressionException(String message) {
        super(message);
    }

    public ExpressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
