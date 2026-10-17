package com.aresstack.spaceharvester.nlp.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreebankAcquisitionTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = exchange.getRequestURI().getPath().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void downloadsVerifiesAndReusesFiles(@TempDir Path directory) {
        String body = "/o/r/" + "c".repeat(40) + "/a.conllu";
        ModelRecipe.Treebank treebank = new ModelRecipe.Treebank("T",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/o/r", "r1", "c".repeat(40),
                List.of(new ModelRecipe.TreebankFile("train", "a.conllu", Hashing.sha256(body))));
        TreebankAcquisition acquisition = new TreebankAcquisition(HttpClient.newHttpClient(), message -> { });
        acquisition.acquire(treebank, directory);
        acquisition.acquire(treebank, directory);
        assertEquals(1, requests.get(), "zweiter Lauf nutzt die geprüfte Datei");
        TreebankAcquisition.verify(treebank, directory);
    }

    @Test
    void rejectsWrongChecksumWithoutKeepingTheFile(@TempDir Path directory) {
        ModelRecipe.Treebank treebank = new ModelRecipe.Treebank("T",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/o/r", "r1", "c".repeat(40),
                List.of(new ModelRecipe.TreebankFile("train", "a.conllu", "0".repeat(64))));
        TreebankAcquisition acquisition = new TreebankAcquisition(HttpClient.newHttpClient(), message -> { });
        assertThrows(IllegalStateException.class, () -> acquisition.acquire(treebank, directory));
        assertEquals(1, requests.get(), "falsche Prüfsumme wird nicht wiederholt");
        assertFalse(Files.exists(directory.resolve("a.conllu")));
        assertFalse(Files.exists(directory.resolve("a.conllu.part")));
    }
}
