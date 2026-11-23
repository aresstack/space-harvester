package com.aresstack.spaceharvester.deck.ndv;

import java.util.List;
import java.util.Map;

/**
 * Gruppen der Objektarten wie im Natural Navigator von NaturalONE, übernommen aus MainframeMate
 * ({@code NdvConnectionTab.TYPE_GROUP_ORDER} und {@code ObjectType.getInstanceIdGroupName()}): feste Reihenfolge,
 * Namen in der Mehrzahl. Die Kennzahlen sind die des NDV-Servers.
 */
public final class NdvTypeGroups {

    public static final int GDA = 1;
    public static final int LDA = 2;
    public static final int PDA = 4;
    public static final int DDM = 8;
    public static final int PROGRAM = 16;
    public static final int SUBPROGRAM = 32;
    public static final int MAP = 64;
    public static final int COPYCODE = 128;
    public static final int SUBROUTINE = 256;
    public static final int HELPROUTINE = 512;
    public static final int CLASS = 1024;
    public static final int DIALOG = 2048;
    public static final int TEXT = 4096;
    public static final int NCP = 8192;
    public static final int ADAPTVIEW = 16384;
    public static final int ERRMSG = 32768;
    public static final int RESOURCE = 65536;
    public static final int FUNCTION = 524288;
    public static final int ADAPTER = 2097152;

    /** Reihenfolge der Gruppen (wie NaturalONE). */
    public static final List<Integer> ORDER = List.of(PROGRAM, SUBPROGRAM, SUBROUTINE, HELPROUTINE, FUNCTION,
            COPYCODE, LDA, GDA, PDA, MAP, DDM, CLASS, DIALOG, TEXT, NCP, ADAPTVIEW, ADAPTER, ERRMSG, RESOURCE);

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(GDA, "Global Data Areas"), Map.entry(LDA, "Local Data Areas"),
            Map.entry(PDA, "Parameter Data Areas"), Map.entry(DDM, "DDMs"), Map.entry(PROGRAM, "Programs"),
            Map.entry(SUBPROGRAM, "Subprograms"), Map.entry(MAP, "Maps"), Map.entry(COPYCODE, "Copycodes"),
            Map.entry(SUBROUTINE, "Subroutines"), Map.entry(HELPROUTINE, "Helproutines"),
            Map.entry(CLASS, "Classes"), Map.entry(DIALOG, "Dialogs"), Map.entry(TEXT, "Texts"),
            Map.entry(NCP, "Command Processors"), Map.entry(ADAPTVIEW, "Adapt Views"),
            Map.entry(FUNCTION, "Functions"), Map.entry(ADAPTER, "Adapters"),
            Map.entry(ERRMSG, "Error Messages"), Map.entry(RESOURCE, "Resources"));

    private NdvTypeGroups() {
    }

    /** Name der Gruppe in der Mehrzahl, für unbekannte Arten „Sonstige“. */
    public static String groupName(int typeId) {
        return NAMES.getOrDefault(typeId, "Sonstige");
    }

    /** Rang in der Gruppenreihenfolge; unbekannte Arten kommen ans Ende. */
    public static int rank(int typeId) {
        int index = ORDER.indexOf(typeId);
        return index < 0 ? ORDER.size() : index;
    }
}
