package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeConnect;

public final class PalTypeConnect extends PalType implements IPalTypeConnect {
    private static final long serialVersionUID = 1L;
    private String benutzer;
    private String kennwort;
    private String befehlszeile;

    public PalTypeConnect(String benutzer, String kennwort, String befehlszeile) {
        super(); typSchluessel = 1;
        this.benutzer = benutzer; this.kennwort = kennwort; this.befehlszeile = befehlszeile;
    }

    public void serialize() { textInPuffer(benutzer); textInPuffer(kennwort); textInPuffer(befehlszeile); }
    public void restore() { /* server does not send this typSchluessel */ }

    public String getUser() { return benutzer; }
    public String getPassword() { return kennwort; }
}
