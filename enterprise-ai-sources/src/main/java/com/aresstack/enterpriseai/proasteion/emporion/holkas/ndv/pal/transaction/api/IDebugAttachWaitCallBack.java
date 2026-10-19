package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeDbgaRecord;

/**
 * Callback-Schnittstelle für Debug-Attach-Wartevorgang.
 */
public interface IDebugAttachWaitCallBack {

    boolean isAborted();

    void recordFound(IPalTypeDbgaRecord record);
}

