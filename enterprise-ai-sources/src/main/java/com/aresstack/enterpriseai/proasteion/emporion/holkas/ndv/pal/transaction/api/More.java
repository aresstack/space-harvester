package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeStream;

public class More {
    private final IPalTypeStream bildschirmInhalt = null;
    private String befehle;

    public final String getCommands() {
        return befehle;
    }

    public final IPalTypeStream getScreen() {
        return bildschirmInhalt;
    }

    public final void setCommands(String commands) {
        this.befehle = commands;
    }
}
