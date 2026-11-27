package com.aresstack.spaceharvester.provingground.expressions;

/** A piece of a template: plain text, or the body of one {@code {{...}}} block (from WD4J). */
record Token(Type type, String content) {

    enum Type { TEXT, EXPR }
}
