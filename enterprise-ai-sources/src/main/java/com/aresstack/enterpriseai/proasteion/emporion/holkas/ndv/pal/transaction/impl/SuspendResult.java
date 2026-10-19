package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.impl;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.PalTypeDbgNatStack;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.PalTypeDbgStackFrame;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeDbgSpy;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeDbgStatus;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeNotify;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeStream;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.ISuspendResult;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.PalResultException;

public final class SuspendResult implements ISuspendResult {
    private IPalTypeDbgStatus debugZustand;
    private PalTypeDbgStackFrame[] aufrufStapelRahmen;
    private IPalTypeDbgSpy ueberwachung;
    private IPalTypeStream bildschirmInhalt;
    private IPalTypeNotify benachrichtigung;
    private byte dezimalZeichen;
    private PalResultException fehler;
    private PalTypeDbgNatStack[] naturalStapelEintraege;

    public SuspendResult(IPalTypeDbgStatus status, PalTypeDbgStackFrame[] stackFrames,
                         IPalTypeDbgSpy spy, IPalTypeStream screen,
                         IPalTypeNotify notify, byte decimalCharacter,
                         PalResultException exception, PalTypeDbgNatStack[] natStackEntries) {
        this.debugZustand = status;
        this.aufrufStapelRahmen = stackFrames != null ? stackFrames.clone() : null;
        this.ueberwachung = spy;
        this.bildschirmInhalt = screen;
        this.benachrichtigung = notify;
        this.dezimalZeichen = decimalCharacter;
        this.fehler = exception;
        this.naturalStapelEintraege = natStackEntries != null ? natStackEntries.clone() : null;
    }

    public IPalTypeNotify getNotify() {
        return benachrichtigung;
    }

    public void setNotify(IPalTypeNotify notify) {
        this.benachrichtigung = notify;
    }

    public byte getDecimalCharacter() {
        return dezimalZeichen;
    }

    public IPalTypeDbgSpy getSpy() {
        return ueberwachung;
    }

    public PalTypeDbgStackFrame[] getStackFrames() {
        return aufrufStapelRahmen != null ? aufrufStapelRahmen.clone() : null;
    }

    public void setStackFrames(PalTypeDbgStackFrame[] stackFrames) {
        if (stackFrames != null) {
            this.aufrufStapelRahmen = stackFrames.clone();
        }
    }

    public IPalTypeDbgStatus getStatus() {
        return debugZustand;
    }

    public PalResultException getException() {
        return fehler;
    }

    public void setException(PalResultException exception) {
        this.fehler = exception;
    }

    public IPalTypeStream getScreen() {
        return bildschirmInhalt;
    }

    public void setScreen(IPalTypeStream screen) {
        this.bildschirmInhalt = screen;
    }

    public PalTypeDbgNatStack[] getNatStackEntries() {
        return naturalStapelEintraege != null ? naturalStapelEintraege.clone() : null;
    }

    public void setNatStackEntries(PalTypeDbgNatStack[] natStackEntries) {
        if (natStackEntries != null) {
            this.naturalStapelEintraege = natStackEntries.clone();
        }
    }
}
