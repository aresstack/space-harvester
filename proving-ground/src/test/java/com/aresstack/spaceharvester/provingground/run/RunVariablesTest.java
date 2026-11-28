package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionException;
import com.aresstack.spaceharvester.provingground.expressions.UnresolvedSymbolException;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVariablesTest {

    private static final TestUser SB = new TestUser("sb", "Fachverfahren/SB", "");

    @Test
    void innerLevelsShadowOuterOnesAndSeeTheirValues() throws ExpressionException {
        RunVariables variables = new RunVariables(Credentials.NONE);
        variables.enter("Projekt", List.of());
        variables.set(List.of(ScopeEntry.variable("x", "projekt"), ScopeEntry.variable("p", "P")), null);
        variables.enter("Suite", List.of());
        variables.set(List.of(ScopeEntry.variable("x", "suite-{{x}}")), null);
        variables.enter("Fall", List.of());
        assertEquals("suite-projekt/P", variables.resolve("{{x}}/{{p}}", null));
        variables.set(List.of(ScopeEntry.variable("x", "fall"),
                ScopeEntry.variable("y", "{{x}}!").withEnabled(true),
                ScopeEntry.variable("z", "aus").withEnabled(false)), null);
        assertEquals("fall!", variables.resolve("{{y}}", null), "later entries see earlier ones");
        assertThrows(UnresolvedSymbolException.class, () -> variables.resolve("{{z}}", null));
        variables.leave();
        assertEquals("suite-projekt", variables.resolve("{{x}}", null), "the test case's values are gone");
    }

    @Test
    void templatesAreResolvedOnEveryUseAndMayNotReferToThemselves() throws ExpressionException {
        RunVariables variables = new RunVariables(Credentials.NONE);
        variables.enter("Projekt", List.of(ScopeEntry.variable("jetzt", "{{n}}-{{Echo('t')}}"),
                ScopeEntry.variable("loop", "{{loop}}")));
        variables.set(List.of(ScopeEntry.variable("n", "1")), null);
        assertEquals("1-t", variables.resolve("{{jetzt}}", null));
        variables.set(List.of(ScopeEntry.variable("n", "2")), null);
        assertEquals("2-t", variables.resolve("{{jetzt}}", null));
        ExpressionException loop = assertThrows(ExpressionException.class, () -> variables.resolve("{{loop}}", null));
        assertTrue(loop.getMessage().contains("loop"));
    }

    @Test
    void userAndSecretsComeFromTheVaultAndAreMasked() throws ExpressionException {
        AtomicInteger reads = new AtomicInteger();
        RunVariables variables = new RunVariables(user -> new UserCredentials("sb.login",
                () -> {
                    reads.incrementAndGet();
                    return "geheim";
                }, null));
        variables.enter("Projekt", List.of());
        assertEquals(0, reads.get(), "nothing is read before it is needed");
        assertEquals("sb/sb.login/geheim", variables.resolve("{{user}}/{{user.login}}/{{secret.Passwort}}", SB));
        assertEquals(1, reads.get());
        assertEquals("expected {{secret}} got x", variables.redact("expected geheim got x"));
        UnresolvedSymbolException none = assertThrows(UnresolvedSymbolException.class,
                () -> variables.resolve("{{secret.Passwort}}", null));
        assertTrue(none.getMessage().contains("kein Benutzer"));
    }

    /** More passwords on one page: {{secret.PIN}} is the entry's field "PIN", every other name its password. */
    @Test
    void aFurtherPasswordIsTheEntrysFieldOfThatNameAndMasked() throws ExpressionException {
        RunVariables variables = new RunVariables(user -> new UserCredentials("sb.login", () -> "geheim", null,
                field -> field.equals("PIN") ? "4711" : null));
        variables.enter("Projekt", List.of());
        assertEquals("geheim/4711", variables.resolve("{{secret.Passwort}}/{{secret.PIN}}", SB));
        assertEquals("{{secret}} {{secret}}", variables.redact("geheim 4711"));
    }
}
