package com.aresstack.enterpriseai.source.api;

import java.util.regex.Pattern;

/**
 * Optionale Fähigkeit eines Quellen-Providers: Das Adressmuster des Quelltyps ist bereits bekannt, bevor eine
 * konkrete Quelle geöffnet wird. Das ist insbesondere für verzögert geöffnete Quellen wichtig, deren Endpoint erst
 * aus dem Tresor gelesen wird.
 */
public interface AddressableKnowledgeSourceProvider {

    /** Vollständiges Eingabemuster des Quelltyps oder {@code null}, wenn er keine direkte Adressierung anbietet. */
    Pattern addressPattern();
}
