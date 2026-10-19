package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeCmdGuard;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeLibId;

/**
 * Schnittstelle für Bibliotheksinformationen.
 */
public interface ILibraryInfo {

    IPalTypeLibId[] getStepLibs();

    EPrivatePrefixType getPrivatePrefixType();

    String getPrivatePrefix();

    IPalTypeCmdGuard getCmdGuard();
}
