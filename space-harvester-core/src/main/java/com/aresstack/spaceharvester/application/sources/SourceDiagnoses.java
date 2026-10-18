package com.aresstack.spaceharvester.application.sources;

import java.util.Optional;

/** Verbindungsdiagnose konfigurierter Quellen in Stufen (#189); prüft keine Rechte, das tut der Aufrufer. */
public interface SourceDiagnoses {

    /**
     * Geht live den Weg der Quelle; legt nichts ab.
     *
     * @return leer ohne Quelle dieser ID
     */
    Optional<SourceDiagnosis> diagnose(String sourceId);
}
