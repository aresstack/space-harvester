package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KeePassRPC über WebSocket: SRP-Pairing, Key-Challenge-Response-Login und verschlüsseltes JSON-RPC.
 *
 * <p>Protokoll und Nachrichtenformat aus MainframeMate {@code KeePassRpcClient} und
 * {@code KeePassRpcPairingDialog} (dort gegen KeePass 2.x/KeePassRPC erprobt, Protokoll {@code 1.7.2}, Referenz:
 * <a href="https://forum.kee.pm/t/keepassrpc-technical-detail/2364">KeePassRPC technical detail</a> und der
 * Go-Client {@code gkp}). Lesen wie dort; Anlegen/Ändern/Löschen wie MainframeMate {@code addLogin}/
 * {@code updateLogin}/{@code removeEntry} für die Passwortverwaltung. Abweichungen: kein Logging von Nachrichteninhalten (MainframeMate loggte
 * entschlüsselte Antworten auf FINE, also inklusive Passwörtern), Einträge nur bei exakt passendem Titel statt
 * "erster Treffer der URL-Suche", Base64 über {@code java.util.Base64} statt {@code javax.xml.bind}.
 */
final class WebSocketKeePassRpcTransport implements KeePassRpcTransport {

    /** Protokollversion {1,7,2} gepackt als (1&lt;&lt;16)|(7&lt;&lt;8)|2. */
    static final int PROTOCOL_VERSION = (1 << 16) | (7 << 8) | 2;
    private static final int SECURITY_LEVEL = 2;

    private final KeePassRpcConfig config;
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();

    WebSocketKeePassRpcTransport(KeePassRpcConfig config) {
        this.config = config;
    }

    @Override
    public char[] pair(KeePassPairingCallback callback) throws KeePassRpcException {
        try (KeePassRpcChannel channel = KeePassRpcChannel.open(config)) {
            KeePassRpcCrypto.SrpClient srp = new KeePassRpcCrypto.SrpClient(random);
            JsonObject identify = new JsonObject();
            identify.addProperty("stage", "identifyToServer");
            identify.addProperty("I", config.clientId());
            identify.addProperty("A", srp.aHex());
            identify.addProperty("securityLevel", SECURITY_LEVEL);
            JsonObject challenge = setupExchange(channel, setup("srp", identify), "SRP identifyToServer");
            rejectError(challenge, "SRP identifyToServer");
            JsonObject serverSrp = object(challenge, "srp");
            String salt = string(serverSrp, "s");
            String b = string(serverSrp, "B");
            if (salt == null || b == null) {
                throw protocol("unerwartete SRP-Antwort (kein s/B)");
            }

            char[] password = callback.requestPairingPassword(config.clientDisplayName());
            if (password == null) {
                return null;
            }
            KeePassRpcCrypto.Proof proof;
            try {
                proof = srp.prove(salt, b, password);
            } finally {
                Arrays.fill(password, '\0');
            }
            if (!channel.isOpen()) {
                throw new KeePassRpcException(KeePassRpcException.Kind.NOT_AVAILABLE,
                        "KeePassRPC hat die Pairing-Verbindung während der Eingabe geschlossen");
            }

            JsonObject proofToServer = new JsonObject();
            proofToServer.addProperty("stage", "proofToServer");
            proofToServer.addProperty("M", proof.mHex());
            proofToServer.addProperty("securityLevel", SECURITY_LEVEL);
            JsonObject verify = setupExchange(channel, setup("srp", proofToServer), "SRP proofToServer");
            rejectError(verify, "SRP proofToServer");
            // M2 ist Pflicht (wie im Go-Client gkp): ohne Server-Beweis wäre der Schlüssel mit einem
            // beliebigen Gegenüber ausgehandelt.
            String m2 = string(object(verify, "srp"), "M2");
            if (m2 == null || !m2.equalsIgnoreCase(proof.expectedM2())) {
                throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED,
                        "Server-Beweis M2 fehlt oder stimmt nicht; Pairing verworfen");
            }
            return proof.sessionKey();
        }
    }

    @Override
    public Session open(char[] sessionKey) throws KeePassRpcException {
        KeePassRpcChannel channel = KeePassRpcChannel.open(config);
        try {
            authenticate(channel, sessionKey);
            return new RpcSession(channel, Arrays.copyOf(sessionKey, sessionKey.length));
        } catch (KeePassRpcException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /** Key-Challenge-Response mit dem beim Pairing gespeicherten Schlüssel. */
    private void authenticate(KeePassRpcChannel channel, char[] sessionKey) throws KeePassRpcException {
        KeePassRpcCrypto.keyBytes(sessionKey); // Formatprüfung vor dem ersten Netzwerkverkehr
        JsonObject identify = new JsonObject();
        identify.addProperty("username", config.clientId());
        identify.addProperty("securityLevel", SECURITY_LEVEL);
        JsonObject challenge = setupExchange(channel, setup("key", identify), "KCR identify");
        rejectError(challenge, "KCR identify");
        String sc = string(object(challenge, "key"), "sc");
        if (sc == null) {
            throw protocol("unerwartete KCR-Antwort (kein key.sc)");
        }
        String cc = KeePassRpcCrypto.newClientChallenge(random);

        JsonObject response = new JsonObject();
        response.addProperty("cc", cc);
        response.addProperty("cr", KeePassRpcCrypto.challengeResponse("1", sessionKey, sc, cc));
        response.addProperty("securityLevel", SECURITY_LEVEL);
        JsonObject verify = setupExchange(channel, setup("key", response), "KCR proof");
        rejectError(verify, "KCR proof");
        // sr ist Pflicht: erst damit beweist der Server, dass er den Pairing-Schlüssel kennt.
        String sr = string(object(verify, "key"), "sr");
        if (sr == null || !sr.equalsIgnoreCase(KeePassRpcCrypto.challengeResponse("0", sessionKey, sc, cc))) {
            throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED,
                    "Server-Antwort sr fehlt oder stimmt nicht");
        }
    }

    private String setup(String section, JsonObject content) {
        JsonObject message = new JsonObject();
        message.addProperty("protocol", "setup");
        message.addProperty("version", PROTOCOL_VERSION);
        message.addProperty("clientTypeId", config.clientId());
        message.addProperty("clientDisplayName", config.clientDisplayName());
        message.addProperty("clientDisplayDescription", config.clientDisplayName());
        JsonArray features = new JsonArray();
        features.add("KPRPC_FEATURE_VERSION_1_6");
        features.add("KPRPC_FEATURE_WARN_USER_WHEN_FEATURE_MISSING");
        features.add("KPRPC_ENTRIES_WITH_NO_URL");
        message.add("features", features);
        message.add(section, content);
        return gson.toJson(message);
    }

    /**
     * Sendet eine Setup-Nachricht und liefert die nächste Setup- oder Fehlerantwort; dazwischen eintreffende
     * Nachrichten anderer Protokolle (Signale) werden übersprungen.
     */
    private static JsonObject setupExchange(KeePassRpcChannel channel, String message, String step)
            throws KeePassRpcException {
        long deadline = channel.newDeadline();
        channel.send(message, step);
        while (true) {
            JsonObject reply = parse(channel.receive(step, deadline));
            String protocol = string(reply, "protocol");
            if (protocol == null || "setup".equals(protocol) || "error".equals(protocol)) {
                return reply;
            }
        }
    }

    /** Fehler im Setup-Protokoll bedeuten: Pairing bzw. Schlüssel abgelehnt. */
    private static void rejectError(JsonObject message, String step) throws KeePassRpcException {
        if (message.has("error") && !message.get("error").isJsonNull()) {
            throw new KeePassRpcException(KeePassRpcException.Kind.AUTH_FAILED,
                    "KeePassRPC lehnt " + step + " ab (" + errorCode(message.get("error")) + ")");
        }
    }

    /** Nur Code/Typ eines Serverfehlers, nie der komplette Text. */
    private static String errorCode(JsonElement error) {
        if (error.isJsonObject()) {
            JsonObject object = error.getAsJsonObject();
            String code = string(object, "code");
            if (code != null) {
                return "Code " + code;
            }
        }
        return error.isJsonPrimitive() && error.getAsJsonPrimitive().isString() ? error.getAsString() : "Fehlerobjekt";
    }

    static JsonObject parse(String json) throws KeePassRpcException {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw protocol("Antwort ist kein JSON-Objekt");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw protocol("Antwort ist kein gültiges JSON");
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Feldwert, wenn er ein JSON-String (oder eine Zahl) ist, sonst {@code null}; wirft nie. */
    static String string(JsonObject parent, String name) {
        JsonElement element = parent == null ? null : parent.get(name);
        return element != null && element.isJsonPrimitive() && !element.getAsJsonPrimitive().isBoolean()
                ? element.getAsString() : null;
    }

    private static KeePassRpcException protocol(String message) {
        return new KeePassRpcException(KeePassRpcException.Kind.PROTOCOL, message);
    }

    @Override
    public String toString() {
        return "KeePassRPC " + config.host() + ":" + config.port();
    }

    /** Authentifizierte Verbindung mit verschlüsseltem JSON-RPC. */
    private final class RpcSession implements Session {

        private final KeePassRpcChannel channel;
        private final char[] sessionKey;
        private final AtomicInteger ids = new AtomicInteger(1);

        RpcSession(KeePassRpcChannel channel, char[] sessionKey) {
            this.channel = channel;
            this.sessionKey = sessionKey;
        }

        @Override
        public KeePassEntry findEntryByTitle(String title) throws KeePassRpcException {
            KeePassEntry entry = KeePassRpcEntries.exactTitleMatch(findLogins(title), title);
            if (entry != null) {
                return entry;
            }
            // FindLogins sucht über URLs und Freitext; Einträge ohne passende URL findet erst GetAllEntries.
            return KeePassRpcEntries.exactTitleMatch(call("GetAllEntries", new JsonArray()), title);
        }

        /**
         * Wie MainframeMate {@code KeePassRpcClient.updateLogin}: den vollständigen Eintrag holen, im
         * {@code formFieldList} nur das eine Feld setzen und per {@code UpdateLogin(login, uniqueID, 2, dbFileName)}
         * zurückschreiben; Benutzer, Passwort, URLs und übrige Felder bleiben, wie sie sind.
         */
        @Override
        public boolean setFormField(String title, String name, String value) throws KeePassRpcException {
            JsonObject existing = KeePassRpcEntries.exactTitleObject(findLogins(title), title);
            if (existing == null) {
                existing = KeePassRpcEntries.exactTitleObject(call("GetAllEntries", new JsonArray()), title);
            }
            if (existing == null) {
                return false;
            }
            String uniqueId = string(existing, "uniqueID");
            if (uniqueId == null || uniqueId.trim().isEmpty()) {
                throw protocol("KeePass-Eintrag ohne uniqueID");
            }
            JsonObject login = KeePassRpcEntries.withFormField(existing, name, value);
            String dbFile = string(existing, "dbFileName");
            if (dbFile == null || dbFile.isEmpty()) {
                dbFile = databaseFileName();
            }
            JsonArray params = new JsonArray();
            params.add(login);
            params.add(uniqueId);
            params.add(2); // urlMergeMode 2 = ersetzen (die URLs des Eintrags gehen unverändert mit)
            params.add(dbFile.isEmpty() ? "*" : dbFile); // der Server verlangt einen nicht leeren Namen
            callAny("UpdateLogin", params);
            return true;
        }

        /** Wie MainframeMate {@code getDatabaseFileName}: GetDatabaseFileName, sonst GetDatabaseName, sonst leer. */
        private String databaseFileName() {
            for (String method : new String[] {"GetDatabaseFileName", "GetDatabaseName"}) {
                try {
                    JsonElement result = callAny(method, new JsonArray());
                    if (result != null && result.isJsonPrimitive() && !result.getAsString().isEmpty()) {
                        return result.getAsString();
                    }
                } catch (KeePassRpcException e) {
                    // nächster Versuch
                }
            }
            return "";
        }

        // FindLogins(unsanitizedURLs, actionURL, httpRealm, loginSearchType, requireFullURLMatches,
        //            uniqueID, dbFileName, freeTextSearch, username)
        private JsonArray findLogins(String title) throws KeePassRpcException {
            JsonArray params = new JsonArray();
            JsonArray urls = new JsonArray();
            urls.add(title);
            params.add(urls);
            params.add("");
            params.add("");
            params.add("LSTall");
            params.add(false);
            params.add("");
            params.add("");
            params.add(title);
            params.add("");
            return call("FindLogins", params);
        }

        @Override
        public java.util.List<KeePassEntry> allEntries() throws KeePassRpcException {
            return KeePassRpcEntries.withoutSecrets(call("GetAllEntries", new JsonArray()));
        }

        @Override
        public void addEntry(KeePassEntry entry) throws KeePassRpcException {
            // AddLogin(login, parentUUID, dbFileName): leer = Wurzelgruppe der aktiven Datenbank
            JsonArray params = new JsonArray();
            params.add(KeePassRpcEntries.login(entry));
            params.add("");
            params.add("");
            callAny("AddLogin", params);
        }

        @Override
        public void updateEntry(KeePassEntry entry, KeePassEntry existing) throws KeePassRpcException {
            // UpdateLogin(login, oldLoginUUID, urlMergeMode, dbFileName); KeePassRPC verlangt einen Dateinamen
            JsonObject login = KeePassRpcEntries.login(entry);
            login.addProperty("uniqueID", existing.uniqueId());
            JsonArray params = new JsonArray();
            params.add(login);
            params.add(existing.uniqueId());
            params.add(2); // 2 = URLs ersetzen
            params.add(databaseFile(existing));
            callAny("UpdateLogin", params);
        }

        @Override
        public void removeEntry(KeePassEntry existing) throws KeePassRpcException {
            // RemoveEntry(uuid, dbFileName)
            JsonArray params = new JsonArray();
            params.add(existing.uniqueId());
            params.add(databaseFile(existing));
            JsonElement result = callAny("RemoveEntry", params);
            if (result != null && result.isJsonPrimitive() && result.getAsJsonPrimitive().isBoolean()
                    && !result.getAsBoolean()) {
                throw protocol("KeePassRPC hat das Löschen abgelehnt");
            }
        }

        /** Datei des Eintrags, sonst die aktive Datenbank, sonst {@code *} wie MainframeMate. */
        private String databaseFile(KeePassEntry existing) {
            if (!existing.databaseFile().isEmpty()) {
                return existing.databaseFile();
            }
            String name = databaseFileName();
            return name.isEmpty() ? "*" : name;
        }

        private JsonArray call(String method, JsonArray params) throws KeePassRpcException {
            JsonElement result = callAny(method, params);
            if (result == null || !result.isJsonArray()) {
                throw protocol("Antwort auf " + method + " ohne Ergebnisliste");
            }
            return result.getAsJsonArray();
        }

        private JsonElement callAny(String method, JsonArray params) throws KeePassRpcException {
            int id = ids.getAndIncrement();
            long deadline = channel.newDeadline();
            channel.send(gson.toJson(wrapper(rpc(method, params, id))), method);
            while (true) {
                JsonObject response = decryptReply(parse(channel.receive(method, deadline)), method);
                if (response == null || !Integer.toString(id).equals(string(response, "id"))) {
                    continue; // Signal oder Antwort auf eine frühere Anfrage: nicht unsere Antwort
                }
                if (response.has("error") && !response.get("error").isJsonNull()) {
                    throw protocol("KeePassRPC-Fehler bei " + method + " (" + errorCode(response.get("error")) + ")");
                }
                return response.get("result");
            }
        }

        private JsonObject rpc(String method, JsonArray params, int id) {
            JsonObject rpc = new JsonObject();
            rpc.addProperty("jsonrpc", "2.0");
            rpc.addProperty("method", method);
            rpc.add("params", params);
            rpc.addProperty("id", id);
            return rpc;
        }

        private JsonObject wrapper(JsonObject rpc) throws KeePassRpcException {
            byte[] plaintext = gson.toJson(rpc).getBytes(StandardCharsets.UTF_8);
            KeePassRpcCrypto.Sealed sealed = KeePassRpcCrypto.encrypt(sessionKey, plaintext, random);
            JsonObject container = new JsonObject();
            Base64.Encoder base64 = Base64.getEncoder();
            container.addProperty("message", base64.encodeToString(sealed.ciphertext));
            container.addProperty("iv", base64.encodeToString(sealed.iv));
            container.addProperty("hmac", base64.encodeToString(sealed.hmac));
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("protocol", "jsonrpc");
            wrapper.addProperty("version", PROTOCOL_VERSION);
            wrapper.add("jsonrpc", container);
            return wrapper;
        }

        /** Entschlüsselter JSON-RPC-Inhalt einer Nachricht oder {@code null} für Nachrichten anderer Protokolle. */
        private JsonObject decryptReply(JsonObject reply, String method) throws KeePassRpcException {
            String protocol = string(reply, "protocol");
            if ("error".equals(protocol)) {
                throw protocol("KeePassRPC meldet Fehler bei " + method
                        + (reply.has("error") ? " (" + errorCode(reply.get("error")) + ")" : ""));
            }
            if (!"jsonrpc".equals(protocol)) {
                return null;
            }
            JsonObject encrypted = object(reply, "jsonrpc");
            String message = string(encrypted, "message");
            String iv = string(encrypted, "iv");
            String hmac = string(encrypted, "hmac");
            if (message == null || iv == null || hmac == null) {
                throw protocol("unverschlüsselte oder unvollständige Antwort auf " + method);
            }
            byte[] plaintext;
            try {
                Base64.Decoder decoder = Base64.getDecoder();
                plaintext = KeePassRpcCrypto.decrypt(sessionKey, new KeePassRpcCrypto.Sealed(
                        decoder.decode(message), decoder.decode(iv), decoder.decode(hmac)));
            } catch (IllegalArgumentException e) {
                throw protocol("ungültiges Base64 in der Antwort auf " + method);
            }
            try {
                return parse(new String(plaintext, StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }

        @Override
        public void close() {
            Arrays.fill(sessionKey, '\0');
            channel.close();
        }
    }
}
