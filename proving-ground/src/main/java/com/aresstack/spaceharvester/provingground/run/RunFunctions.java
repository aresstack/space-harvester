package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionFunction;
import com.aresstack.spaceharvester.provingground.expressions.FunctionExecutionException;
import com.aresstack.spaceharvester.provingground.expressions.FunctionMetadata;
import com.aresstack.spaceharvester.provingground.expressions.FunctionRegistry;
import com.aresstack.spaceharvester.provingground.expressions.ResolutionContext;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.time.Clock;
import java.util.List;

/**
 * The functions of a test run on top of WD4J's built-ins (WD4J's tools, now over the Playwright API):
 *
 * <pre>
 *   {{otp()}}            one-time code of the step's user (vault), {{otp('name')}} of another user
 *   {{js('expression')}} JavaScript on the current page (page.evaluate), e.g. {{js('document.title')}}
 *   {{text('selector')}} the text of an element (Playwright selector, strict)
 *   {{url()}}            the address of the current page
 *   {{title()}}          the title of the current page
 * </pre>
 */
final class RunFunctions {

    private RunFunctions() {
    }

    static FunctionRegistry registry(TestProject project, Clock clock) {
        return FunctionRegistry.builtins().with(
                new Otp(project, clock),
                pageFunction("js", "JavaScript auf der aktuellen Seite auswerten", List.of("expression"),
                        (page, args) -> String.valueOf(page.evaluate(argument(args, "js")))),
                pageFunction("text", "Text eines Elements (Playwright-Selektor)", List.of("selector"),
                        (page, args) -> page.locator(argument(args, "text")).innerText()),
                pageFunction("url", "Adresse der aktuellen Seite", List.of(), (page, args) -> page.url()),
                pageFunction("title", "Titel der aktuellen Seite", List.of(), (page, args) -> page.title()));
    }

    private static String argument(List<String> args, String function) {
        if (args.isEmpty() || args.get(0).isEmpty()) {
            throw new IllegalArgumentException(function + "(): Argument fehlt");
        }
        return args.get(0);
    }

    private interface PageCall {
        String apply(Page page, List<String> args);
    }

    private static ExpressionFunction pageFunction(String name, String description, List<String> parameters,
                                                   PageCall call) {
        FunctionMetadata meta = new FunctionMetadata(name, description, parameters, null);
        return new ExpressionFunction() {
            @Override
            public String invoke(List<String> args, ResolutionContext context) throws FunctionExecutionException {
                Page page = context.service(Page.class);
                if (page == null) {
                    throw new FunctionExecutionException(name + "(): keine Seite offen");
                }
                try {
                    return call.apply(page, args);
                } catch (PlaywrightException | IllegalArgumentException failed) {
                    throw new FunctionExecutionException(name + "(): " + firstLine(failed.getMessage()), failed);
                }
            }

            @Override
            public FunctionMetadata metadata() {
                return meta;
            }
        };
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }

    /** WD4J's 2FA tool: the time-based one-time code from the user's vault entry. */
    private record Otp(TestProject project, Clock clock) implements ExpressionFunction {

        private static final FunctionMetadata META = new FunctionMetadata("otp",
                "Einmalcode (TOTP) des Benutzers aus dem Tresor", List.of("user?"), null);

        @Override
        public String invoke(List<String> args, ResolutionContext context) throws FunctionExecutionException {
            TestUser user = !args.isEmpty() && !args.get(0).isEmpty()
                    ? project.user(args.get(0)) : context.service(TestUser.class);
            if (user == null) {
                throw new FunctionExecutionException("otp(): "
                        + (args.isEmpty() || args.get(0).isEmpty() ? "kein Benutzer" : "Benutzer unbekannt: " + args.get(0)));
            }
            RunVariables variables = context.service(RunVariables.class);
            UserCredentials access = variables == null ? null : variables.credentials(user);
            String secret = access == null ? null : access.otpSecret().get();
            if (secret == null || secret.isBlank()) {
                throw new FunctionExecutionException("otp(): kein OTP-Geheimnis im Tresor für " + user.name());
            }
            try {
                String code = Totp.code(secret, clock.instant());
                variables.secret(code);
                return code;
            } catch (IllegalArgumentException invalid) {
                throw new FunctionExecutionException("otp(): " + invalid.getMessage(), invalid);
            }
        }

        @Override
        public FunctionMetadata metadata() {
            return META;
        }
    }
}
