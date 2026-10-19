package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.PalTypeSystemFile;

public final class PalTypeSystemFileFactory {
    private PalTypeSystemFileFactory() {
    }

    public static IPalTypeSystemFile newInstance(int databaseId, int fileNumber, int kind) {
        return new PalTypeSystemFile(databaseId, fileNumber, kind);
    }

    public static IPalTypeSystemFile newInstance() {
        return new PalTypeSystemFile();
    }
}
