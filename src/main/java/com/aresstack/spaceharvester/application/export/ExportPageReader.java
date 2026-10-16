package com.aresstack.spaceharvester.application.export;

import java.util.Optional;

/**
 * Liest Seiten einer Partition stapelweise aus dem Cache (Lucene, #26), nie live aus der Quelle. Umsetzung und
 * Generationsbegriff kommen aus den Strängen C und D; bis dahin gibt es nur Test-Fakes.
 */
public interface ExportPageReader {

    /** Die aktuelle Generation der Partition oder leer, wenn sie nicht im Cache liegt. */
    Optional<ExportScope> scope(String sourceId, String partitionKey);

    /**
     * Ein Stapel Seiten aus genau der Generation von {@code scope}.
     *
     * @param cursor {@code null} für den ersten Stapel
     * @throws com.aresstack.spaceharvester.application.jobs.JobFailedException wenn die Generation nicht mehr lesbar
     *                                                                         ist; der Export scheitert dann
     */
    ExportPageBatch pages(ExportScope scope, String cursor, int limit);
}
