package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeObject;

/**
 * Ergebnis einer Quellcode-Suche nach Objektname.
 */
public interface ISourceLookupResult {

    IPalTypeObject getObject();

    String getLibrary();

    int getDatabaseId();

    int getFileNumber();
}

