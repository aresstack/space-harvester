package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeNotify;

public final class PalTypeNotify extends PalType implements IPalTypeNotify {
    private static final long serialVersionUID = 1L;
    private int benachrichtigung;
    private int erweiterung;

    public PalTypeNotify() { super(); typSchluessel = 19; }
    public PalTypeNotify(int benachrichtigung) { this(); this.benachrichtigung = benachrichtigung; }

    public void serialize() { ganzzahlInPuffer(benachrichtigung); ganzzahlInPuffer(erweiterung); }
    public void restore() { benachrichtigung = intFromBuffer(); erweiterung = intFromBuffer(); }

    public int getNotification() { return benachrichtigung; }
}
