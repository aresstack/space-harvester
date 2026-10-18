package com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcCrypto.G;
import static com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcCrypto.K;
import static com.aresstack.enterpriseai.proasteion.platform.security.keepassrpc.KeePassRpcCrypto.N;

/**
 * Lokaler KeePassRPC-Server für Tests: serverseitiges SRP-Pairing, Key-Challenge-Response und verschlüsseltes
 * JSON-RPC ({@code FindLogins}, {@code GetAllEntries}) nach der KeePassRPC-Protokollbeschreibung. Prüft wie das
 * Original den Origin-Header.
 */
public final class FakeKeePassRpcServer extends WebSocketServer {

    private static final long FLUSH_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "FakeKeePassRpcServer-flusher");
                    thread.setDaemon(true);
                    return thread;
                }
            });
    private final Gson gson = new Gson();
    private final CountDownLatch started = new CountDownLatch(1);
    private final Map<WebSocket, ConnectionState> states = new ConcurrentHashMap<WebSocket, ConnectionState>();
    private final Map<String, String> pairedKeys = new ConcurrentHashMap<String, String>();
    private final List<JsonObject> entries = new ArrayList<JsonObject>();
    private final List<String> receivedMessages = new ArrayList<String>();

    /** Das Einmal-Passwort, das KeePass beim Pairing anzeigen würde. */
    volatile String pairingPassword = "S3cr3tPairingCode";
    /** FindLogins findet nichts (Eintrag ohne passende URL), nur GetAllEntries liefert ihn. */
    volatile boolean findLoginsEmpty;
    /** Fehlverhalten für Negativtests. */
    volatile boolean omitM2;
    volatile boolean omitSr;
    volatile boolean sendSignalBeforeEachReply;
    volatile boolean resultNotArray;
    volatile boolean messageFieldIsObject;

    public FakeKeePassRpcServer() {
        super(new InetSocketAddress("127.0.0.1", 0));
        setReuseAddr(true);
    }

    public FakeKeePassRpcServer startAndWait() throws InterruptedException {
        start();
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Fake-Server nicht gestartet");
        }
        return this;
    }

    public int boundPort() {
        return getPort();
    }

    /** Legt einen Eintrag an; {@code asFormFields}: Zugangsdaten als Formularfelder statt als Standardfelder. */
    public void addEntry(String title, String userName, String password, boolean asFormFields) {
        JsonObject entry = new JsonObject();
        entry.addProperty("title", title);
        entry.addProperty("uniqueID", Integer.toHexString(title.hashCode()));
        if (asFormFields) {
            JsonArray fields = new JsonArray();
            fields.add(field("FFTusername", userName));
            fields.add(field("FFTpassword", password));
            entry.add("formFieldList", fields);
        } else {
            entry.addProperty("usernameValue", userName);
            entry.addProperty("password", password);
        }
        synchronized (entries) {
            entries.add(entry);
        }
    }

    /** Simuliert "Pairing in KeePass widerrufen". */
    public void revokeAllPairings() {
        pairedKeys.clear();
    }

    String pairedKeyOf(String clientId) {
        return pairedKeys.get(clientId);
    }

    /** Das Einmal-Passwort, das KeePass beim Pairing anzeigen würde (für den Pairing-Callback im Test). */
    public String pairingPassword() {
        return pairingPassword;
    }

    public List<String> receivedMessages() {
        synchronized (receivedMessages) {
            return new ArrayList<String>(receivedMessages);
        }
    }

    @Override
    public void onStart() {
        started.countDown();
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        String origin = handshake.getFieldValue("Origin");
        if (origin == null || !origin.startsWith("chrome-extension://")) {
            connection.close(1008, "origin");
            ensureFlushed((WebSocketImpl) connection, System.nanoTime() + FLUSH_DEADLINE_NANOS);
            return;
        }
        states.put(connection, new ConnectionState());
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        synchronized (receivedMessages) {
            receivedMessages.add(message);
        }
        ConnectionState state = states.get(connection);
        JsonObject request = gson.fromJson(message, JsonObject.class);
        String protocol = request.get("protocol").getAsString();
        if ("setup".equals(protocol) && request.has("srp")) {
            reply(connection, srp(state, request));
        } else if ("setup".equals(protocol) && request.has("key")) {
            reply(connection, kcr(state, request.getAsJsonObject("key")));
        } else if ("jsonrpc".equals(protocol) && state.sessionKey != null) {
            if (sendSignalBeforeEachReply) {
                // Wie KeePassRPC-Signale (z. B. "Datenbank geöffnet"): JSON-RPC ohne passende ID.
                JsonObject signal = new JsonObject();
                signal.addProperty("jsonrpc", "2.0");
                signal.addProperty("method", "KPRPCListener");
                JsonArray params = new JsonArray();
                params.add(3);
                signal.add("params", params);
                reply(connection, seal(state, signal));
            }
            reply(connection, rpc(state, request.getAsJsonObject("jsonrpc")));
        } else {
            reply(connection, "{\"protocol\":\"error\",\"error\":{\"code\":\"UNEXPECTED\"}}");
        }
    }

    /**
     * Sendet eine Antwort und stellt sicher, dass sie auch auf die Leitung kommt.
     *
     * <p>Hintergrund (Java-WebSocket 1.5.x, Serverseite): {@code WebSocketServer.run} entfernt einen nur
     * schreibbereiten Key nicht aus {@code selectedKeys}. Die veraltete Schreibbereitschaft bleibt so an dem Key
     * hängen, und beim nächsten Lesedurchlauf ruft der Selector-Thread nach {@code doRead} zusätzlich
     * {@code doWrite} auf, das die Interest-Ops am Ende auf {@code OP_READ} zurücksetzt. Hat der Worker-Thread
     * die Anfrage in genau diesem Moment schon beantwortet ({@code send} setzt {@code OP_READ|OP_WRITE}),
     * überschreibt der Selector das {@code OP_WRITE}: Die Antwort bleibt in {@code outQueue} liegen, der Selector
     * schläft in {@code select()}, und der Client läuft ohne jede Fehlermeldung in sein Timeout. Unter CPU-Last
     * trat das etwa in einem von 15 bis 40 Testläufen auf (Thread-Dump: Selector in {@code EPoll.wait},
     * {@code outQueue=1}, {@code interestOps=OP_READ}). Der Produktionscode ist nicht betroffen: der
     * WebSocket-Client schreibt in einem eigenen blockierenden Thread.
     *
     * <p>Gegenmaßnahme: Ein Hilfsthread prüft kurz nach dem Senden, ob die Queue geleert wurde, und meldet
     * andernfalls per {@link #onWriteDemand(WebSocket)} das Schreibinteresse erneut an (weckt den Selector).
     * Das läuft bewusst nicht im Worker-Thread, weil der den Lesepuffer des Servers hält.
     */
    private void reply(WebSocket connection, String message) {
        connection.send(message);
        ensureFlushed((WebSocketImpl) connection, System.nanoTime() + FLUSH_DEADLINE_NANOS);
    }

    private void ensureFlushed(final WebSocketImpl connection, final long deadline) {
        flusher.schedule(new Runnable() {
            @Override
            public void run() {
                if (connection.outQueue.isEmpty() || connection.isClosed() || System.nanoTime() > deadline) {
                    return;
                }
                onWriteDemand(connection);
                ensureFlushed(connection, deadline);
            }
        }, 2, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        try {
            super.stop(timeout);
        } finally {
            flusher.shutdownNow();
        }
    }

    private String srp(ConnectionState state, JsonObject request) {
        JsonObject srp = request.getAsJsonObject("srp");
        String stage = srp.get("stage").getAsString();
        if ("identifyToServer".equals(stage)) {
            state.clientId = srp.get("I").getAsString();
            state.aHex = srp.get("A").getAsString();
            state.salt = KeePassRpcCrypto.hex(randomBytes(16));
            BigInteger x = new BigInteger(1, KeePassRpcCrypto.sha256(
                    KeePassRpcCrypto.utf8(state.salt + pairingPassword)));
            state.verifier = G.modPow(x, N);
            state.b = new BigInteger(256, random);
            state.bHex = KeePassRpcCrypto.toHex(K.multiply(state.verifier).add(G.modPow(state.b, N)).mod(N));
            JsonObject reply = new JsonObject();
            reply.addProperty("protocol", "setup");
            JsonObject content = new JsonObject();
            content.addProperty("stage", "identifyToClient");
            content.addProperty("s", state.salt);
            content.addProperty("B", state.bHex);
            reply.add("srp", content);
            return gson.toJson(reply);
        }
        BigInteger a = new BigInteger(state.aHex, 16);
        BigInteger u = new BigInteger(1, KeePassRpcCrypto.sha256(KeePassRpcCrypto.utf8(state.aHex + state.bHex)));
        String sHex = KeePassRpcCrypto.toHex(a.multiply(state.verifier.modPow(u, N)).modPow(state.b, N));
        String expectedM = KeePassRpcCrypto.hex(KeePassRpcCrypto.sha256(
                KeePassRpcCrypto.utf8(state.aHex + state.bHex + sHex)));
        if (!expectedM.equalsIgnoreCase(srp.get("M").getAsString())) {
            return error("AUTH_FAILED");
        }
        pairedKeys.put(state.clientId, KeePassRpcCrypto.hex(KeePassRpcCrypto.sha256(KeePassRpcCrypto.utf8(sHex))));
        JsonObject reply = new JsonObject();
        reply.addProperty("protocol", "setup");
        JsonObject content = new JsonObject();
        content.addProperty("stage", "proofToClient");
        if (!omitM2) {
            content.addProperty("M2", KeePassRpcCrypto.hex(KeePassRpcCrypto.sha256(
                    KeePassRpcCrypto.utf8(state.aHex + expectedM + sHex))));
        }
        reply.add("srp", content);
        return gson.toJson(reply);
    }

    private String kcr(ConnectionState state, JsonObject key) {
        if (key.has("username")) {
            state.clientId = key.get("username").getAsString();
            if (!pairedKeys.containsKey(state.clientId)) {
                return error("UNKNOWN_CLIENT");
            }
            state.sc = new BigInteger(1, randomBytes(32)).toString();
            JsonObject reply = new JsonObject();
            reply.addProperty("protocol", "setup");
            JsonObject content = new JsonObject();
            content.addProperty("sc", state.sc);
            reply.add("key", content);
            return gson.toJson(reply);
        }
        String stored = pairedKeys.get(state.clientId);
        String cc = key.get("cc").getAsString();
        if (stored == null || !KeePassRpcCrypto.challengeResponse("1", stored.toCharArray(), state.sc, cc)
                .equals(key.get("cr").getAsString())) {
            return error("AUTH_FAILED");
        }
        state.sessionKey = stored;
        JsonObject reply = new JsonObject();
        reply.addProperty("protocol", "setup");
        JsonObject content = new JsonObject();
        if (!omitSr) {
            content.addProperty("sr", KeePassRpcCrypto.challengeResponse("0", stored.toCharArray(), state.sc, cc));
        }
        reply.add("key", content);
        return gson.toJson(reply);
    }

    private String rpc(ConnectionState state, JsonObject container) {
        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] plaintext = KeePassRpcCrypto.decrypt(state.sessionKey.toCharArray(), new KeePassRpcCrypto.Sealed(
                    decoder.decode(container.get("message").getAsString()),
                    decoder.decode(container.get("iv").getAsString()),
                    decoder.decode(container.get("hmac").getAsString())));
            JsonObject call = gson.fromJson(new String(plaintext, StandardCharsets.UTF_8), JsonObject.class);
            String method = call.get("method").getAsString();
            JsonArray result = new JsonArray();
            if ("FindLogins".equals(method) && !findLoginsEmpty) {
                String freeText = call.getAsJsonArray("params").get(7).getAsString().toLowerCase();
                synchronized (entries) {
                    for (JsonObject entry : entries) {
                        // Wie KeePassRPC: Freitextsuche liefert auch Teiltreffer, nicht nur exakte Titel.
                        if (entry.get("title").getAsString().toLowerCase().contains(freeText)) {
                            result.add(entry);
                        }
                    }
                }
            } else if ("GetAllEntries".equals(method)) {
                synchronized (entries) {
                    for (JsonObject entry : entries) {
                        result.add(entry);
                    }
                }
            }
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            if (resultNotArray) {
                response.add("result", new JsonObject());
            } else {
                response.add("result", result);
            }
            response.add("id", call.get("id"));
            if (messageFieldIsObject) {
                return "{\"protocol\":\"jsonrpc\",\"jsonrpc\":{\"message\":{},\"iv\":\"AA==\",\"hmac\":\"AA==\"}}";
            }
            return seal(state, response);
        } catch (KeePassRpcException e) {
            return "{\"protocol\":\"error\",\"error\":{\"code\":\"DECRYPT\"}}";
        }
    }

    private String seal(ConnectionState state, JsonObject response) {
        try {
            KeePassRpcCrypto.Sealed sealed = KeePassRpcCrypto.encrypt(state.sessionKey.toCharArray(),
                    gson.toJson(response).getBytes(StandardCharsets.UTF_8), random);
            Base64.Encoder encoder = Base64.getEncoder();
            JsonObject encrypted = new JsonObject();
            encrypted.addProperty("message", encoder.encodeToString(sealed.ciphertext));
            encrypted.addProperty("iv", encoder.encodeToString(sealed.iv));
            encrypted.addProperty("hmac", encoder.encodeToString(sealed.hmac));
            JsonObject reply = new JsonObject();
            reply.addProperty("protocol", "jsonrpc");
            reply.add("jsonrpc", encrypted);
            return gson.toJson(reply);
        } catch (KeePassRpcException e) {
            return "{\"protocol\":\"error\",\"error\":{\"code\":\"DECRYPT\"}}";
        }
    }

    private String error(String code) {
        JsonObject reply = new JsonObject();
        reply.addProperty("protocol", "setup");
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        reply.add("error", error);
        return gson.toJson(reply);
    }

    private static JsonElement field(String type, String value) {
        JsonObject field = new JsonObject();
        field.addProperty("type", type);
        field.addProperty("value", value);
        field.addProperty("name", type);
        return field;
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        states.remove(connection);
    }

    @Override
    public void onError(WebSocket connection, Exception error) {
        // Sichtbar machen: Java-WebSocket schluckt Ausnahmen aus onMessage sonst still (slf4j-nop), und der Client
        // sähe nur ein Timeout.
        System.err.println("FakeKeePassRpcServer: Fehler auf " + connection);
        error.printStackTrace();
    }

    private static final class ConnectionState {
        String clientId;
        String aHex;
        String bHex;
        String salt;
        BigInteger b;
        BigInteger verifier;
        String sc;
        String sessionKey;
    }
}
