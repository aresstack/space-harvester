package com.aresstack.spaceharvester.mainframe.diagram;

import java.util.Set;

/** Standardliste der z/OS-Systemprogramme aus MainframeMate ({@code SystemFunctionSettingsHelper.getDefaults()}). */
public final class SystemFunctions {

    /** Programmnamen in Großbuchstaben. */
    public static final Set<String> DEFAULT_NAMES = Set.of("IDCAMS", "IEFBR14", "SORT", "ICEMAN", "SYNCSORT",
            "IKJEFT01", "IKJEFT1A", "IKJEFT1B", "IRXJCL", "IEBGENER", "IEBCOPY", "IEBUPDTE", "IEHPROGM", "IEHLIST",
            "IEHMOVE", "IEWL", "HEWL", "IGYCRCTL", "ASMA90", "CBCDRVR", "DSNTEP2", "DSNUPROC", "DSNTIAD", "DFHCSDUP",
            "FTP", "ICETOOL", "ICHUT100", "ICHUT200", "ADRDSSU", "GIMSMP");

    private SystemFunctions() {
    }
}
