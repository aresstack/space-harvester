package com.aresstack.spaceharvester.provingground.expressions;

/** A variable or function is not known (from WD4J). The message names it, never a value. */
public class UnresolvedSymbolException extends ExpressionException {

    private final String symbolName;

    public UnresolvedSymbolException(String symbolName) {
        super("Unbekannt: " + symbolName);
        this.symbolName = symbolName;
    }

    public String getSymbolName() {
        return symbolName;
    }
}
