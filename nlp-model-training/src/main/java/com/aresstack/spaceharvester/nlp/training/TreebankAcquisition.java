package com.aresstack.spaceharvester.nlp.training;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Holt die gepinnten Treebank-Dateien über die Commit-URL (nie über einen Branch) und prüft jede SHA-256 sofort.
 * Vorhandene Dateien mit passender Prüfsumme werden nicht neu geladen; so läuft ein zweiter Lauf, oder einer mit
 * vorab abgelegten Dateien ({@code -PtreebankDir=…}), ohne Netz. Eine Datei mit falscher Prüfsumme bricht ab.
 */
public final class TreebankAcquisition {

    private static final int ATTEMPTS = 4;

    private final HttpClient http;
    private final Consumer<String> log;

    public TreebankAcquisition(Consumer<String> log) {
        // Proxy aus https.proxyHost/-Port der JVM, falls gesetzt (Container, Unternehmensnetz)
        this(HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build(), log);
    }

    TreebankAcquisition(HttpClient http, Consumer<String> log) {
        this.http = http;
        this.log = log;
    }

    public void acquire(ModelRecipe.Treebank treebank, Path directory) {
        try {
            Files.createDirectories(directory);
            for (ModelRecipe.TreebankFile file : treebank.files()) {
                Path target = directory.resolve(file.name());
                if (Files.isRegularFile(target) && Hashing.sha256(target).equals(file.sha256())) {
                    log.accept("vorhanden, Prüfsumme stimmt: " + file.name());
                    continue;
                }
                download(URI.create(treebank.downloadUrl(file)), target, file.sha256());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Prüft nur, ohne zu laden: alle Dateien vorhanden und mit der gepinnten Prüfsumme. */
    public static void verify(ModelRecipe.Treebank treebank, Path directory) {
        for (ModelRecipe.TreebankFile file : treebank.files()) {
            Path path = directory.resolve(file.name());
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(path + " fehlt; zuerst prepareGermanTreebank ausführen");
            }
            String actual = Hashing.sha256(path);
            if (!actual.equals(file.sha256())) {
                throw new IllegalStateException(file.name() + ": SHA-256 " + actual + " statt " + file.sha256());
            }
        }
    }

    private void download(URI uri, Path target, String sha256) throws IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                log.accept("lade " + uri);
                HttpResponse<Path> response = http.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofFile(partial));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " für " + uri);
                }
                String actual = Hashing.sha256(partial);
                if (!actual.equals(sha256)) {
                    Files.deleteIfExists(partial);
                    // Eine falsche Prüfsumme ist kein Netzfehler: nicht wiederholen
                    throw new IllegalStateException(uri + ": SHA-256 " + actual + " statt " + sha256);
                }
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException e) {
                last = e;
                log.accept("Versuch " + attempt + " fehlgeschlagen: " + e.getMessage());
                sleep(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("unterbrochen", e);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
        throw last;
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(1000L << attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
