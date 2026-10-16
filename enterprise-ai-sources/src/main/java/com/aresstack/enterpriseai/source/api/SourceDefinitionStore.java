package com.aresstack.enterpriseai.source.api;

import com.aresstack.enterpriseai.astu.source.SourceDefinition;

import java.io.IOException;
import java.util.List;

/**
 * Wo die konfigurierten Quellen liegen (produktiv die Konfigurationsdatei, je Quelle ein Listeneintrag
 * {@code source.<n>.*} mit Adresse, Typ, Häkchen und den Schlüsseln des Adapters). Gefunden wird eine Quelle über
 * ihre kanonische Adresse; die Listenposition ist ein Detail der Ablage. Der Store prüft keine Einstellungen; das tun
 * die {@link KnowledgeSourceProvider} im Use Case.
 */
public interface SourceDefinitionStore {

    /** Die Quellen in ihrer Reihenfolge, so wie sie gespeichert sind (auch fehlerhafte). */
    List<SourceDefinition> definitions() throws IOException;

    /**
     * Speichert eine Quelle: neu hinten angefügt oder an der Stelle der Quelle mit {@code originalAddress} ersetzt
     * (auch unter neuer Adresse). Eine leere {@code originalAddress} meint die erste gespeicherte Quelle desselben
     * Typs ohne Adresse.
     *
     * @param originalAddress die bisherige Adresse beim Bearbeiten, {@code null} beim Hinzufügen
     */
    void save(SourceDefinition definition, String originalAddress) throws IOException;

    /** Nimmt die Quelle mit dieser Adresse aus der Konfiguration. */
    void remove(String address) throws IOException;

    /** Schreibt nur das Häkchen. */
    void setEnabled(String address, boolean enabled) throws IOException;
}
