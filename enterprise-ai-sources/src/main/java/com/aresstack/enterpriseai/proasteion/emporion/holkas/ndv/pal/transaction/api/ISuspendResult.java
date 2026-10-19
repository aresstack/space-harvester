package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.PalTypeDbgNatStack;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.PalTypeDbgStackFrame;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeDbgSpy;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeDbgStatus;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeNotify;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeStream;

/**
 * Schnittstelle für das Ergebnis einer Debug-Unterbrechung (Suspend).
 */
public interface ISuspendResult {

    IPalTypeNotify getNotify();

    void setNotify(IPalTypeNotify notify);

    byte getDecimalCharacter();

    IPalTypeDbgSpy getSpy();

    PalTypeDbgStackFrame[] getStackFrames();

    PalTypeDbgNatStack[] getNatStackEntries();

    void setStackFrames(PalTypeDbgStackFrame[] stackFrames);

    IPalTypeDbgStatus getStatus();

    PalResultException getException();

    void setException(PalResultException exception);

    IPalTypeStream getScreen();

    void setScreen(IPalTypeStream screen);
}

