package com.aresstack.spaceharvester.mainframe.diagram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Standardliste der z/OS-Systemprogramme aus MainframeMate ({@code SystemFunctionSettingsHelper.getDefaults()}). */
public final class SystemFunctions {

    /** Die Standardeinträge in der Reihenfolge von MainframeMate (dort stand IEFBR14 doppelt; hier einmal). */
    public static final List<SystemFunction> DEFAULTS = List.of(
            new SystemFunction("IDCAMS", "Access_Method_Services", "Access_Method_Services", "Access Method Services — VSAM-Verwaltung (DEFINE, DELETE, REPRO, LISTCAT, ALTER)"),
            new SystemFunction("IEFBR14", "IEFBR14", "IEFBR14", "Null-Programm — allokiert/löscht Datasets ohne Verarbeitung"),
            new SystemFunction("SORT", "DFSORT", "DFSORT", "DFSORT — Sortieren, Mischen, Kopieren von Datasets"),
            new SystemFunction("ICEMAN", "DFSORT", "DFSORT", "DFSORT ICEMAN — alternativer Aufrufer für DFSORT"),
            new SystemFunction("SYNCSORT", "Syncsort", "Syncsort", "Syncsort — Sortier-Utility (Drittanbieter)"),
            new SystemFunction("IKJEFT01", "Time_Sharing_Option", "Time_Sharing_Option", "TSO/E Batch — führt TSO-Befehle und REXX im Batch aus"),
            new SystemFunction("IKJEFT1A", "Time_Sharing_Option", "Time_Sharing_Option", "TSO/E Batch (IKJEFT1A) — wie IKJEFT01, setzt RC=12 bei ABEND"),
            new SystemFunction("IKJEFT1B", "Time_Sharing_Option", "Time_Sharing_Option", "TSO/E Batch (IKJEFT1B) — wie IKJEFT01, setzt RC=16 bei ABEND"),
            new SystemFunction("IRXJCL", "REXX", "Rexx", "REXX im Batch — führt REXX-Prozeduren unter TSO aus"),
            new SystemFunction("IEBGENER", "IEBGENER", "IEBGENER", "Dataset Copy/Print — kopiert sequentielle Datasets"),
            new SystemFunction("IEBCOPY", "IEBCOPY", "IEBCOPY", "PDS Copy/Compress — kopiert und komprimiert PDS-Bibliotheken"),
            new SystemFunction("IEBUPDTE", "IEBUPDTE", "IEBUPDTE", "Sequential Update — fügt Members in PDS ein oder aktualisiert sie"),
            new SystemFunction("IEHPROGM", "IEHPROGM", "IEHPROGM", "Katalogverwaltung — Scratch, Rename, Catalog, Uncatalog"),
            new SystemFunction("IEHLIST", "IEHLIST", "IEHLIST", "Catalog Listing — listet VTOC und Katalogeinträge"),
            new SystemFunction("IEHMOVE", "IEHMOVE", "IEHMOVE", "Dataset Move/Copy — verschiebt oder kopiert Datasets"),
            new SystemFunction("IEWL", "Linkage_Editor", "Linkage_editor", "Linkage Editor — bindet Object Modules zu Load Modules"),
            new SystemFunction("HEWL", "Linkage_Editor", "Linkage_editor", "Linkage Editor (alias HEWL)"),
            new SystemFunction("IGYCRCTL", "COBOL", "COBOL", "Enterprise COBOL Compiler"),
            new SystemFunction("ASMA90", "Assembler_(Informatik)", "Assembly_language#Assembler", "High Level Assembler (HLASM)"),
            new SystemFunction("CBCDRVR", "C_(Programmiersprache)", "C_(programming_language)", "z/OS XL C/C++ Compiler Driver"),
            new SystemFunction("DSNTEP2", "Db2", "IBM_Db2", "DB2 Dynamic SQL Batch — führt SQL-Anweisungen im Batch aus"),
            new SystemFunction("DSNUPROC", "Db2", "IBM_Db2", "DB2 Utilities — LOAD, UNLOAD, REORG, RUNSTATS, COPY, RECOVER"),
            new SystemFunction("DSNTIAD", "Db2", "IBM_Db2", "DB2 Dynamic SQL Processor"),
            new SystemFunction("DFHCSDUP", "Customer_Information_Control_System", "CICS", "CICS CSD Utility — verwaltet CICS System Definition Datasets"),
            new SystemFunction("FTP", "File_Transfer_Protocol", "File_Transfer_Protocol", "z/OS FTP Client — Dateiübertragung"),
            new SystemFunction("ICETOOL", "DFSORT", "DFSORT", "DFSORT ICETOOL — erweiterte Sortier- und Berichtsoperationen"),
            new SystemFunction("ICHUT100", "Resource_Access_Control_Facility", "Resource_Access_Control_Facility", "RACF Utility — Datenbank-Entlade-Utility"),
            new SystemFunction("ICHUT200", "Resource_Access_Control_Facility", "Resource_Access_Control_Facility", "RACF Utility — Cross-Reference-Utility"),
            new SystemFunction("ADRDSSU", "DFSMSdss", "DFSMSdss", "DFSMSdss — Dump, Restore, Copy, Print von Datasets und Volumes"),
            new SystemFunction("GIMSMP", "System_Modification_Program/Extended", "System_Modification_Program/Extended", "SMP/E — System Modification Program/Extended"),
            new SystemFunction("ICKDSF", "ICKDSF", "ICKDSF", "Device Support Facilities — DASD-Initialisierung und -Verwaltung"));

    /** Programmnamen der Standardliste in Großbuchstaben. */
    public static final Set<String> DEFAULT_NAMES = names(DEFAULTS);

    private SystemFunctions() {
    }

    /** Die Namen einer Liste (Großbuchstaben). */
    public static Set<String> names(List<SystemFunction> functions) {
        return functions.stream().map(SystemFunction::name).filter(n -> !n.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Nachschlagen nach Namen wie {@code buildLookup()}: der letzte Eintrag eines Namens gilt. */
    public static Map<String, SystemFunction> lookup(List<SystemFunction> functions) {
        Map<String, SystemFunction> map = new LinkedHashMap<>();
        for (SystemFunction function : functions) {
            if (!function.name().isEmpty()) {
                map.put(function.name(), function);
            }
        }
        return map;
    }
}
