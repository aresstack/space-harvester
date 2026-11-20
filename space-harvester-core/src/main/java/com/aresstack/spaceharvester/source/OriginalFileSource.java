package com.aresstack.spaceharvester.source;

import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Ein Adapter, der das Original einer Datei in eine Datei auf der Platte lädt statt in den Speicher (Angelo,
 * 20.11.2026): für den Textauszug von Dateien über der Größengrenze des Caches ({@code LargeFiles}).
 */
interface OriginalFileSource {

    /**
     * Lädt das Original nach {@code target}, wenn sein Medientyp {@code wanted} erfüllt; den Typ liest der Adapter vor
     * dem Herunterladen aus den Kopfdaten der Quelle.
     *
     * @param maxBytes größte Datei, die geladen wird; größere enden mit {@code INVALID_RESPONSE}
     * @return der Medientyp des geladenen Originals; leer, wenn der Typ nicht gewollt ist (dann wurde nichts geladen)
     */
    Optional<String> downloadOriginal(String localId, Path target, Predicate<String> wanted, long maxBytes)
            throws KnowledgeSourceException;
}
