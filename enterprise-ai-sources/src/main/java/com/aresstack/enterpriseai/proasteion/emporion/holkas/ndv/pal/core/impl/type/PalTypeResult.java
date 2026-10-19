package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeResult;

public final class PalTypeResult extends PalType implements IPalTypeResult {
    private static final long serialVersionUID = 1L;
    private int naturalErgebnis;
    private int systemErgebnis;

    public PalTypeResult() { super(); typSchluessel = 10; }

    public void serialize() { /* server-only */ }
    public void restore() {
        naturalErgebnis = intFromBuffer();
        systemErgebnis = intFromBuffer();
        intFromBuffer(); // discarded
    }

    public int getNaturalResult() { return naturalErgebnis; }
    public int getSystemResult() { return systemErgebnis; }
}
