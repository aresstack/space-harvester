package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

/**
 * Schnittstelle für PAL-Benutzereinstellungen.
 */
public interface IPalPreferences {

    int getTimeOut();

    boolean replaceLineNoRefsWithLabels();

    boolean createLabelsInNewLine();

    String getLabelFormat();

    boolean checkTimeStamp();
}

