package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Liest Benutzername und Passwort aus KeePassRPC-Login-DTOs (Version 1). Feldreihenfolge wie MainframeMate
 * {@code KeePassRpcClient.getUserName/getPassword}: direkte Felder, sonst {@code formFieldList} mit
 * {@code FFTusername}/{@code FFTpassword}.
 */
final class KeePassRpcEntries {

    private KeePassRpcEntries() {
    }

    /** Erster Eintrag, dessen Titel (getrimmt, ohne Groß-/Kleinschreibung) dem gesuchten entspricht. */
    static KeePassEntry exactTitleMatch(JsonElement result, String title) {
        if (result == null || !result.isJsonArray()) {
            return null;
        }
        String wanted = title.trim();
        for (JsonElement element : result.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String entryTitle = string(entry, "title");
            if (entryTitle != null && wanted.equalsIgnoreCase(entryTitle.trim())) {
                String password = firstNonNull(string(entry, "password"), formField(entry, "FFTpassword"));
                char[] passwordChars = password == null ? new char[0] : password.toCharArray();
                try {
                    return new KeePassEntry(entryTitle, userName(entry),
                            passwordChars, location(entry), attributes(entry), string(entry, "uniqueID"),
                            databaseFile(entry), url(entry));
                } finally {
                    java.util.Arrays.fill(passwordChars, '\0');
                }
            }
        }
        return null;
    }

    /** Das rohe Login-DTO des ersten Eintrags mit genau diesem Titel oder {@code null} (zum Zurückschreiben). */
    static JsonObject exactTitleObject(JsonElement result, String title) {
        if (result == null || !result.isJsonArray()) {
            return null;
        }
        String wanted = title.trim();
        for (JsonElement element : result.getAsJsonArray()) {
            if (element.isJsonObject()) {
                String entryTitle = string(element.getAsJsonObject(), "title");
                if (entryTitle != null && wanted.equalsIgnoreCase(entryTitle.trim())) {
                    return element.getAsJsonObject();
                }
            }
        }
        return null;
    }

    /**
     * Kopie des Login-DTOs mit gesetztem Formularfeld {@code name}: ein vorhandenes Feld dieses Namens bekommt den
     * Wert, sonst kommt eines dazu, wie MainframeMate {@code KeePassRpcClient.addCustomFormField} es anlegt
     * ({@code name}, {@code displayName}, {@code value}, {@code type=FFTtext}, {@code id=""}, {@code page=-1}).
     */
    static JsonObject withFormField(JsonObject entry, String name, String value) {
        JsonObject copy = entry.deepCopy();
        String text = value == null ? "" : value;
        JsonElement listElement = copy.get("formFieldList");
        com.google.gson.JsonArray fields = listElement != null && listElement.isJsonArray()
                ? listElement.getAsJsonArray() : new com.google.gson.JsonArray();
        boolean found = false;
        for (JsonElement element : fields) {
            if (element.isJsonObject() && name.equals(string(element.getAsJsonObject(), "name"))) {
                element.getAsJsonObject().addProperty("value", text);
                found = true;
            }
        }
        if (!found) {
            JsonObject field = new JsonObject();
            field.addProperty("name", name);
            field.addProperty("displayName", name);
            field.addProperty("value", text);
            field.addProperty("type", "FFTtext");
            field.addProperty("id", "");
            field.addProperty("page", -1);
            fields.add(field);
        }
        copy.add("formFieldList", fields);
        return copy;
    }

    /** Alle Einträge mit Titel, Benutzer, Adresse und Zusatzfeldern, ohne Passwort (Auswahlliste, Verwaltung). */
    static java.util.List<KeePassEntry> withoutSecrets(JsonElement result) {
        java.util.List<KeePassEntry> entries = new java.util.ArrayList<KeePassEntry>();
        if (result == null || !result.isJsonArray()) {
            return entries;
        }
        for (JsonElement element : result.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String title = string(entry, "title");
            if (title != null && !title.trim().isEmpty()) {
                entries.add(new KeePassEntry(title.trim(), userName(entry), new char[0], location(entry),
                        attributes(entry), string(entry, "uniqueID"), databaseFile(entry), url(entry)));
            }
        }
        return entries;
    }

    /**
     * Erste http(s)-Adresse aus {@code uRLs} (bzw. {@code url}), wie MainframeMate sie liest. MainframeMate legt
     * Einträge ohne URL mit dem Titel als Platzhalter an; solche Werte sind keine Adresse.
     */
    static String location(JsonObject entry) {
        java.util.List<String> candidates = new java.util.ArrayList<String>();
        for (String name : new String[] {"uRLs", "url", "URLs"}) {
            JsonElement value = entry.get(name);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                candidates.add(value.getAsString());
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        candidates.add(item.getAsString());
                    }
                }
            }
        }
        for (String candidate : candidates) {
            String trimmed = candidate == null ? "" : candidate.trim();
            String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return trimmed;
            }
        }
        for (String candidate : candidates) {
            String trimmed = candidate == null ? "" : candidate.trim();
            if (trimmed.contains("://")) {
                return trimmed; // z. B. ftp://host für Mainframe-Einträge
            }
        }
        return null;
    }

    /**
     * Die benannten Felder aus {@code formFieldList} ohne Benutzer- und Passwortfeld, wie MainframeMate
     * {@code KeePassRpcClient.formFieldByName} sie für {@code MM_CertAlias}, {@code MM_RequiresLogin} usw. liest.
     */
    static java.util.Map<String, String> attributes(JsonObject entry) {
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<String, String>();
        JsonElement fields = entry.get("formFieldList");
        if (fields == null || !fields.isJsonArray()) {
            return attributes;
        }
        for (JsonElement element : fields.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject field = element.getAsJsonObject();
            String type = string(field, "type");
            String name = string(field, "name");
            String value = string(field, "value");
            if (name == null || name.trim().isEmpty() || value == null
                    || "FFTpassword".equals(type) || "FFTusername".equals(type)) {
                continue;
            }
            if (!attributes.containsKey(name.trim())) {
                attributes.put(name.trim(), value);
            }
        }
        return attributes;
    }

    private static String userName(JsonObject entry) {
        return firstNonNull(string(entry, "usernameValue"), string(entry, "username"), formField(entry, "FFTusername"));
    }

    /**
     * Die erste URL des Eintrags wie gespeichert; der Titel als Platzhalter (MainframeMate, Einträge ohne URL) zählt
     * nicht.
     */
    static String url(JsonObject entry) {
        String title = string(entry, "title");
        for (String name : new String[] {"uRLs", "url", "URLs"}) {
            JsonElement value = entry.get(name);
            JsonElement first = value != null && value.isJsonArray() && value.getAsJsonArray().size() > 0
                    ? value.getAsJsonArray().get(0) : value;
            if (first != null && first.isJsonPrimitive()) {
                String url = first.getAsString().trim();
                if (!url.isEmpty() && (title == null || !url.equals(title.trim()))) {
                    return url;
                }
                return "";
            }
        }
        return "";
    }

    /** Datenbankdatei wie MainframeMate sie liest: {@code dbFileName}, sonst {@code db}. */
    static String databaseFile(JsonObject entry) {
        String file = firstNonNull(string(entry, "dbFileName"), string(entry, "db"));
        if (file == null) {
            JsonElement db = entry.get("db");
            if (db != null && db.isJsonObject()) {
                file = string(db.getAsJsonObject(), "fileName");
            }
        }
        return file == null ? "" : file;
    }

    /**
     * Login-DTO (Version 1) für {@code AddLogin}/{@code UpdateLogin} wie MainframeMate
     * {@code KeePassRpcClient.addLogin}: Benutzer und Passwort als {@code FFTusername}/{@code FFTpassword}, jedes
     * Zusatzfeld als {@code FFTtext}; ohne Adresse steht der Titel als Platzhalter in {@code uRLs}, damit
     * {@code FindLogins} den Eintrag findet.
     */
    static JsonObject login(KeePassEntry entry) {
        com.google.gson.JsonArray formFields = new com.google.gson.JsonArray();
        formFields.add(formFieldObject("KeePass username", entry.userName(), "FFTusername", ""));
        formFields.add(formFieldObject("KeePass password", new String(entry.password()), "FFTpassword", ""));
        for (java.util.Map.Entry<String, String> field : entry.attributes().entrySet()) {
            formFields.add(formFieldObject(field.getKey(), field.getValue(), "FFTtext", field.getKey()));
        }
        JsonObject login = new JsonObject();
        login.addProperty("title", entry.title());
        login.add("formFieldList", formFields);
        if (!entry.uniqueId().isEmpty()) {
            login.addProperty("uniqueID", entry.uniqueId());
        }
        com.google.gson.JsonArray urls = new com.google.gson.JsonArray();
        String location = entry.url();
        urls.add(location.isEmpty() ? entry.title() : location);
        login.add("uRLs", urls);
        return login;
    }

    private static JsonObject formFieldObject(String name, String value, String type, String id) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("displayName", name);
        field.addProperty("value", value == null ? "" : value);
        field.addProperty("type", type);
        field.addProperty("id", id);
        field.addProperty("page", -1);
        return field;
    }

    private static String formField(JsonObject entry, String type) {
        JsonElement fields = entry.get("formFieldList");
        if (fields == null || !fields.isJsonArray()) {
            return null;
        }
        for (JsonElement element : fields.getAsJsonArray()) {
            if (element.isJsonObject() && type.equals(string(element.getAsJsonObject(), "type"))) {
                return string(element.getAsJsonObject(), "value");
            }
        }
        return null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
