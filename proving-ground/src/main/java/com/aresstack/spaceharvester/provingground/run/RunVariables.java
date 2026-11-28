package com.aresstack.spaceharvester.provingground.run;

import com.aresstack.spaceharvester.provingground.expressions.ExpressionException;
import com.aresstack.spaceharvester.provingground.expressions.FunctionRegistry;
import com.aresstack.spaceharvester.provingground.expressions.ResolutionContext;
import com.aresstack.spaceharvester.provingground.expressions.ResolveTemplateUseCase;
import com.aresstack.spaceharvester.provingground.expressions.UnresolvedSymbolException;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.microsoft.playwright.Page;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The symbol table of a test run (WD4J's {@code RuntimeVariableContext}): one level each for the project, the suite and
 * the test case (a precondition adds one on top while it runs), searched from the inside out. Each level holds the
 * values of its before hooks (resolved once) and its templates (resolved on every use).
 *
 * <p>Besides, for the user a step runs as: {@code {{user}}} (the user's name in the project), {@code {{user.login}}}
 * (the login name from the vault) and {@code {{secret.<field>}}} (the vault entry's field of that name, otherwise its
 * password; the recorder writes this for every password field). Every secret and one-time code handed out is remembered only to mask it in
 * messages.</p>
 */
public final class RunVariables {

    /** The variable of the user's name in the project. */
    public static final String USER = "user";
    /** The variable of the login name from the vault. */
    public static final String USER_LOGIN = "user.login";
    /** Prefix of the password variables the recorder writes. */
    public static final String SECRET_PREFIX = "secret.";

    private final Deque<Level> levels = new ArrayDeque<>();
    private final Credentials credentials;
    private final Set<String> secrets = new LinkedHashSet<>();
    private FunctionRegistry functions = FunctionRegistry.builtins();
    private Supplier<Page> page = () -> null;

    public RunVariables(Credentials credentials) {
        this.credentials = credentials == null ? Credentials.NONE : credentials;
    }

    /** Starts a level (project, suite, test case, precondition) with its templates. */
    public void enter(String label, List<ScopeEntry> templates) {
        Level level = new Level(label);
        for (ScopeEntry template : templates) {
            if (template.enabled()) {
                level.templates.put(template.name(), template.expression());
            }
        }
        levels.push(level);
    }

    /** Ends the innermost level. */
    public void leave() {
        levels.pop();
    }

    /** Ends the innermost level and keeps it (values and templates), to {@link #enter(Level)} it again later. */
    Level leaveKeeping() {
        return levels.pop();
    }

    /** Puts a level kept by {@link #leaveKeeping()} back on top. */
    void enter(Level level) {
        levels.push(level);
    }

    /** Resolves {@code entries} as {@code user} and sets them on the innermost level, in order (later see earlier). */
    public void set(List<ScopeEntry> entries, TestUser user) throws ExpressionException {
        for (ScopeEntry entry : entries) {
            if (entry.enabled()) {
                setOn(0, entry, user);
            }
        }
    }

    /** Resolves {@code entry} as {@code user} and sets it on the level {@code outward} steps out from the innermost. */
    public void setOn(int outward, ScopeEntry entry, TestUser user) throws ExpressionException {
        String value = resolve(entry.expression(), user);
        int index = 0;
        for (Level level : levels) {
            if (index++ == outward) {
                level.values.put(entry.name(), value);
                return;
            }
        }
        throw new IllegalStateException("Keine Ebene " + outward);
    }

    /** {@code template} resolved as {@code user}. */
    public String resolve(String template, TestUser user) throws ExpressionException {
        return ResolveTemplateUseCase.resolve(template, context(user));
    }

    /** The context in which expressions are resolved for steps of {@code user} (null = no user). */
    public ResolutionContext context(TestUser user) {
        return new UserContext(user, new ArrayList<>());
    }

    /** The functions expressions may call (the runner adds those that need the page or the vault). */
    void functions(FunctionRegistry registry) {
        functions = registry;
    }

    /** Where functions find the page the test is on. */
    void page(Supplier<Page> current) {
        page = current;
    }

    UserCredentials credentials(TestUser user) {
        return user == null ? null : credentials.of(user);
    }

    /** Remembers a secret handed out, to mask it in messages. */
    void secret(String value) {
        if (value != null && !value.isEmpty()) {
            secrets.add(value);
        }
    }

    /** {@code text} with every secret handed out so far replaced by "{{secret}}". */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, "{{secret}}");
        }
        return result;
    }

    static final class Level {
        final String label;
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> templates = new LinkedHashMap<>();

        Level(String label) {
            this.label = label;
        }
    }

    /** One user's view; {@code resolving} guards against templates that refer to themselves. */
    private final class UserContext implements ResolutionContext {

        private final TestUser user;
        private final List<String> resolving;

        UserContext(TestUser user, List<String> resolving) {
            this.user = user;
            this.resolving = resolving;
        }

        @Override
        public boolean isAvailable(String name) {
            for (Level level : levels) {
                if (level.values.containsKey(name) || level.templates.containsKey(name)) {
                    return true;
                }
            }
            return USER.equals(name) && user != null
                    || (USER_LOGIN.equals(name) || name.startsWith(SECRET_PREFIX)) && credentials(user) != null;
        }

        @Override
        public Object resolveVariable(String name) throws ExpressionException {
            for (Level level : levels) {
                if (level.values.containsKey(name)) {
                    return level.values.get(name);
                }
                if (level.templates.containsKey(name)) {
                    if (resolving.contains(name)) {
                        throw new ExpressionException("Vorlage verweist auf sich selbst: " + name);
                    }
                    resolving.add(name);
                    try {
                        return ResolveTemplateUseCase.resolve(level.templates.get(name),
                                new UserContext(user, resolving));
                    } finally {
                        resolving.remove(name);
                    }
                }
            }
            if (USER.equals(name) && user != null) {
                return user.name();
            }
            if (USER_LOGIN.equals(name) || name.startsWith(SECRET_PREFIX)) {
                UserCredentials access = credentials(user);
                String value = access == null ? null
                        : USER_LOGIN.equals(name) ? access.login() : access.secret(name.substring(SECRET_PREFIX.length()));
                if (value == null) {
                    throw new UnresolvedSymbolException(name + (user == null ? " (kein Benutzer)"
                            : " (nicht im Tresor für " + user.name() + ")"));
                }
                if (!USER_LOGIN.equals(name)) {
                    secret(value);
                }
                return value;
            }
            throw new UnresolvedSymbolException(name);
        }

        @Override
        public FunctionRegistry functions() {
            return functions;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T service(Class<T> type) {
            if (type == Page.class) {
                return (T) page.get();
            }
            if (type == TestUser.class) {
                return (T) user;
            }
            if (type == RunVariables.class) {
                return (T) RunVariables.this;
            }
            return null;
        }
    }
}
