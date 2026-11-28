package com.aresstack.spaceharvester.provingground.store;

import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;
import com.aresstack.spaceharvester.provingground.model.StepBinding;
import com.aresstack.spaceharvester.provingground.model.StepRole;
import com.aresstack.spaceharvester.provingground.model.TestCase;
import com.aresstack.spaceharvester.provingground.model.TestCaseEntry;
import com.aresstack.spaceharvester.provingground.model.TestProject;
import com.aresstack.spaceharvester.provingground.model.TestSuite;
import com.aresstack.spaceharvester.provingground.model.TestUser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The test project as {@code projekt.json} in the project directory: suites, test cases, hooks, preconditions, users
 * (references only) and the subject matter per step id. Each test case's actions are in its own Playwright JSONL file
 * ({@link TestCaseFile}), named relative to the project directory, by default {@code <suite>/<case>.jsonl}.
 *
 * <pre>
 * {"provingGround":1,"name":"Fachverfahren","defaultUser":"sachbearbeiter",
 *  "users":[{"name":"sachbearbeiter","vaultEntry":"Fachverfahren/Test-SB"}],
 *  "scope":{"beforeAll":[{"name":"heute","expression":"{{Date('dd.MM.yyyy')}}"}]},
 *  "preconditions":["anmeldung/login"],
 *  "suites":[{"id":"anmeldung","name":"Anmeldung","cases":[
 *     {"id":"login","name":"Anmelden","file":"anmeldung/login.jsonl",
 *      "steps":{"7d0c…":{"role":"GIVEN","description":"Startseite öffnen"}}}]}]}
 * </pre>
 *
 * Nothing confidential belongs here: passwords and one-time secrets stay in the vault.
 */
public final class ProjectFile {

    public static final String FILE_NAME = "projekt.json";
    public static final int FORMAT = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private ProjectFile() {
    }

    /** The default file of a new test case: {@code <suite>/<case>.jsonl}. */
    public static String defaultCaseFile(String suiteId, String caseId) {
        return suiteId + "/" + caseId + TestCaseFile.EXTENSION;
    }

    /** The JSONL file of {@code testCase} in the project directory {@code directory}. */
    public static Path caseFile(Path directory, TestCaseEntry testCase) throws IOException {
        Path file = directory.resolve(testCase.file()).normalize();
        if (!file.startsWith(directory.normalize())) {
            throw new IOException("Testfall " + testCase.id() + ": Datei außerhalb des Projekts: " + testCase.file());
        }
        return file;
    }

    /**
     * Where the reference images of {@code testCase} are, as Playwright names the folder: {@code <case>-snapshots}
     * next to its file (e.g. {@code login/anmelden-snapshots/}); null if the file lies outside the project.
     */
    public static Path screenshotReferences(Path directory, TestCaseEntry testCase) {
        try {
            Path file = caseFile(directory, testCase);
            String name = file.getFileName().toString();
            String stem = name.endsWith(TestCaseFile.EXTENSION)
                    ? name.substring(0, name.length() - TestCaseFile.EXTENSION.length()) : name;
            return file.resolveSibling(stem + "-snapshots");
        } catch (IOException outside) {
            return null;
        }
    }

    /** Reads the actions of {@code testCase}. */
    public static TestCase readCase(Path directory, TestCaseEntry testCase) throws IOException {
        return TestCaseFile.read(caseFile(directory, testCase));
    }

    public static void write(TestProject project, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(toJson(project)) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException noAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static TestProject read(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException broken) {
            throw new IOException(file + ": kein JSON-Objekt", broken);
        }
        if (!json.has("provingGround")) {
            throw new IOException(file + ": keine Proving-Ground-Projektdatei");
        }
        int format = json.get("provingGround").getAsInt();
        if (format > FORMAT) {
            throw new IOException(file + ": von einem neueren Proving Ground geschrieben (Format " + format + ")");
        }
        try {
            return fromJson(json);
        } catch (IllegalArgumentException | IllegalStateException | ClassCastException
                 | UnsupportedOperationException | DateTimeParseException broken) {
            throw new IOException(file + ": " + broken.getMessage(), broken);
        }
    }

    // ---- JSON ----

    static JsonObject toJson(TestProject project) {
        JsonObject json = new JsonObject();
        json.addProperty("provingGround", FORMAT);
        json.addProperty("name", project.name());
        if (project.defaultUser() != null) {
            json.addProperty("defaultUser", project.defaultUser());
        }
        JsonArray users = new JsonArray();
        for (TestUser user : project.users()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", user.name());
            putIfNotEmpty(entry, "vaultEntry", user.vaultEntry());
            putIfNotEmpty(entry, "description", user.description());
            users.add(entry);
        }
        json.add("users", users);
        putScope(json, project.scope());
        putList(json, "preconditions", project.preconditions());
        JsonArray suites = new JsonArray();
        for (TestSuite suite : project.suites()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", suite.id());
            entry.addProperty("name", suite.name());
            putIfNotEmpty(entry, "description", suite.description());
            putScope(entry, suite.scope());
            putList(entry, "preconditions", suite.preconditions());
            JsonArray cases = new JsonArray();
            for (TestCaseEntry testCase : suite.cases()) {
                cases.add(toJson(testCase));
            }
            entry.add("cases", cases);
            suites.add(entry);
        }
        json.add("suites", suites);
        return json;
    }

    private static JsonObject toJson(TestCaseEntry testCase) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", testCase.id());
        entry.addProperty("name", testCase.name());
        entry.addProperty("file", testCase.file());
        if (testCase.user() != null) {
            entry.addProperty("user", testCase.user());
        }
        putIfNotEmpty(entry, "description", testCase.description());
        putScope(entry, testCase.scope());
        putList(entry, "preconditions", testCase.preconditions());
        if (!testCase.steps().isEmpty()) {
            JsonObject steps = new JsonObject();
            testCase.steps().forEach((stepId, binding) -> {
                JsonObject step = new JsonObject();
                if (binding.role() != null) {
                    step.addProperty("role", binding.role().name());
                }
                if (binding.user() != null) {
                    step.addProperty("user", binding.user());
                }
                putIfNotEmpty(step, "description", binding.description());
                if (!binding.minDuration().isZero()) {
                    step.addProperty("minDuration", binding.minDuration().toString());
                }
                steps.add(stepId, step);
            });
            entry.add("steps", steps);
        }
        return entry;
    }

    private static void putScope(JsonObject json, Scope scope) {
        if (scope.isEmpty()) {
            return;
        }
        JsonObject entry = new JsonObject();
        putEntries(entry, "beforeAll", scope.beforeAll());
        putEntries(entry, "beforeEach", scope.beforeEach());
        putEntries(entry, "templates", scope.templates());
        putEntries(entry, "afterEach", scope.afterEach());
        putEntries(entry, "afterAll", scope.afterAll());
        json.add("scope", entry);
    }

    private static void putEntries(JsonObject json, String key, List<ScopeEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (ScopeEntry scopeEntry : entries) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", scopeEntry.name());
            entry.addProperty("expression", scopeEntry.expression());
            if (!scopeEntry.enabled()) {
                entry.addProperty("enabled", false);
            }
            putIfNotEmpty(entry, "description", scopeEntry.description());
            if (scopeEntry.check() != null) {
                JsonObject check = new JsonObject();
                check.addProperty("type", scopeEntry.check().type());
                check.addProperty("expected", scopeEntry.check().expected());
                entry.add("check", check);
            }
            array.add(entry);
        }
        json.add(key, array);
    }

    private static void putList(JsonObject json, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add(key, array);
    }

    private static void putIfNotEmpty(JsonObject json, String key, String value) {
        if (value != null && !value.isEmpty()) {
            json.addProperty(key, value);
        }
    }

    static TestProject fromJson(JsonObject json) {
        List<TestUser> users = new ArrayList<>();
        for (JsonObject user : objects(json, "users")) {
            users.add(new TestUser(string(user, "name"), string(user, "vaultEntry"), string(user, "description")));
        }
        List<TestSuite> suites = new ArrayList<>();
        for (JsonObject suite : objects(json, "suites")) {
            List<TestCaseEntry> cases = new ArrayList<>();
            for (JsonObject testCase : objects(suite, "cases")) {
                cases.add(testCase(testCase));
            }
            suites.add(new TestSuite(string(suite, "id"), string(suite, "name"), scope(suite),
                    strings(suite, "preconditions"), cases, string(suite, "description")));
        }
        return new TestProject(string(json, "name"), scope(json), strings(json, "preconditions"), users,
                string(json, "defaultUser"), suites);
    }

    private static TestCaseEntry testCase(JsonObject json) {
        Map<String, StepBinding> steps = new LinkedHashMap<>();
        if (json.has("steps") && json.get("steps").isJsonObject()) {
            for (Map.Entry<String, JsonElement> step : json.getAsJsonObject("steps").entrySet()) {
                JsonObject binding = step.getValue().getAsJsonObject();
                String role = string(binding, "role");
                String minDuration = string(binding, "minDuration");
                steps.put(step.getKey(), new StepBinding(
                        role == null ? null : StepRole.valueOf(role.toUpperCase(Locale.ROOT)),
                        string(binding, "user"), string(binding, "description"),
                        minDuration == null ? Duration.ZERO : Duration.parse(minDuration)));
            }
        }
        return new TestCaseEntry(string(json, "id"), string(json, "name"), string(json, "file"), string(json, "user"),
                scope(json), strings(json, "preconditions"), steps, string(json, "description"));
    }

    private static Scope scope(JsonObject json) {
        if (!json.has("scope") || !json.get("scope").isJsonObject()) {
            return Scope.EMPTY;
        }
        JsonObject scope = json.getAsJsonObject("scope");
        return new Scope(entries(scope, "beforeAll"), entries(scope, "beforeEach"), entries(scope, "templates"),
                entries(scope, "afterEach"), entries(scope, "afterAll"));
    }

    private static List<ScopeEntry> entries(JsonObject json, String key) {
        List<ScopeEntry> entries = new ArrayList<>();
        for (JsonObject entry : objects(json, key)) {
            Check check = null;
            if (entry.has("check") && entry.get("check").isJsonObject()) {
                JsonObject c = entry.getAsJsonObject("check");
                check = new Check(string(c, "type"), string(c, "expected"));
            }
            boolean enabled = !entry.has("enabled") || entry.get("enabled").getAsBoolean();
            entries.add(new ScopeEntry(string(entry, "name"), string(entry, "expression"), enabled,
                    string(entry, "description"), check));
        }
        return entries;
    }

    private static List<JsonObject> objects(JsonObject json, String key) {
        List<JsonObject> objects = new ArrayList<>();
        if (json.has(key) && json.get(key).isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray(key)) {
                objects.add(element.getAsJsonObject());
            }
        }
        return objects;
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> values = new ArrayList<>();
        if (json.has(key) && json.get(key).isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray(key)) {
                values.add(element.getAsString());
            }
        }
        return values;
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
