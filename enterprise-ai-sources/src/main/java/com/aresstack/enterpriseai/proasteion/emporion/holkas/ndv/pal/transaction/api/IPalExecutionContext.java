package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeLibId;

/**
 * Schnittstelle für den Anwendungsausführungskontext.
 */
public interface IPalExecutionContext {

    IPalTypeLibId[] getLibrarySearchOrder(String library);

    EStepLibFormat getLibrarySearchOrderFormat(String library);
}

