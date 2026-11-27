package com.aresstack.spaceharvester.provingground.expressions;

/**
 * Resolves a whole string with embedded {@code {{...}}} expressions (from WD4J): one entry point for steps and hooks.
 * Text without "{{" is returned as it is.
 */
public final class ResolveTemplateUseCase {

    private static final ExpressionParser PARSER = new ExpressionParser();

    private final ResolutionContext context;

    public ResolveTemplateUseCase(ResolutionContext context) {
        this.context = context;
    }

    public String resolveTemplate(String template) throws ExpressionException {
        return resolve(template, context);
    }

    /** {@code template} resolved in {@code context}; null stays null. */
    public static String resolve(String template, ResolutionContext context) throws ExpressionException {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        Object result = PARSER.parseTemplate(template).resolve(context);
        return result == null ? "" : result.toString();
    }

    /** Whether {@code text} contains an expression. */
    public static boolean hasExpression(String text) {
        return text != null && text.contains("{{");
    }
}
