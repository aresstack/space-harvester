package com.aresstack.spaceharvester.provingground.expressions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionParserTest {

    private static final ExpressionFunction WRAP = new ExpressionFunction() {
        @Override
        public String invoke(List<String> args, ResolutionContext context) {
            return "[" + String.join("|", args) + "]";
        }

        @Override
        public FunctionMetadata metadata() {
            return new FunctionMetadata("wrap", "test", List.of("a", "b"), null);
        }
    };

    private final ResolutionContext context = new MapResolutionContext(
            Map.of("userName", "angelo", "code", "42"), FunctionRegistry.builtins().with(WRAP));

    @Test
    void textVariablesAndNestedFunctionsAsInWd4j() throws ExpressionException {
        assertEquals("Hallo angelo, Code 42", ResolveTemplateUseCase.resolve("Hallo {{userName}}, Code {{code}}",
                context));
        assertEquals("[x|angelo]", ResolveTemplateUseCase.resolve("{{wrap('x';{{userName}})}}", context));
        assertEquals("[y|[x|angelo]]", ResolveTemplateUseCase.resolve("{{wrap(\"y\";{{wrap('x';{{userName}})}})}}",
                context));
        assertEquals("[angelo|a;b]", ResolveTemplateUseCase.resolve("{{wrap(userName;'a;b')}}", context),
                "bare names are variables, ';' inside quotes is text");
        assertEquals("[]", ResolveTemplateUseCase.resolve("{{wrap()}}", context));
        assertEquals("a b", ResolveTemplateUseCase.resolve("{{ECHO('a';'b')}}", context), "names ignore case");
        assertEquals("plain {text}", ResolveTemplateUseCase.resolve("plain {text}", context));
    }

    @Test
    void theTreeIsBuiltWithoutResolvingAnything() {
        ResolvableExpression tree = new ExpressionParser().parseTemplate("a{{x}}{{f({{y}})}}");
        CompositeExpression composite = assertInstanceOf(CompositeExpression.class, tree);
        assertEquals(3, composite.getParts().size());
        FunctionExpression call = assertInstanceOf(FunctionExpression.class, composite.getParts().get(2));
        assertEquals("f", call.getFunctionName());
        assertFalse(tree.isResolved(context));
        assertTrue(new ExpressionParser().parseTemplate("{{userName}}").isResolved(context));
    }

    @Test
    void suppliersAreAskedOnEveryUse() throws ExpressionException {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> fresh = () -> Integer.toString(calls.incrementAndGet());
        ResolutionContext lazy = new MapResolutionContext(Map.of("otp", fresh));
        assertEquals("1", ResolveTemplateUseCase.resolve("{{otp}}", lazy));
        assertEquals("2", ResolveTemplateUseCase.resolve("{{otp}}", lazy));
    }

    @Test
    void failuresNameTheSymbolOrPosition() {
        UnresolvedSymbolException missing = assertThrows(UnresolvedSymbolException.class,
                () -> ResolveTemplateUseCase.resolve("{{secret.Pin}}", context));
        assertEquals("Unbekannt: secret.Pin", missing.getMessage());
        assertThrows(UnresolvedSymbolException.class, () -> ResolveTemplateUseCase.resolve("{{nope()}}", context));
        IllegalArgumentException open = assertThrows(IllegalArgumentException.class,
                () -> ResolveTemplateUseCase.resolve("a {{b", context));
        assertTrue(open.getMessage().contains("3"));
        assertThrows(FunctionExecutionException.class,
                () -> ResolveTemplateUseCase.resolve("{{Date('qqq')}}", context));
    }
}
