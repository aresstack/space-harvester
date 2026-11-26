package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectKind;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectType;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException;
import com.aresstack.enterpriseai.source.api.KnowledgeSourceException.Kind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Resolve explicitly selected DDMs by system file and authoritative long name. */
final class NdvDdmScope {

    private static final String PREFIX = "FDDM:";

    private NdvDdmScope() {
    }

    static boolean isStartPoint(String value) {
        return value != null && PREFIX.equalsIgnoreCase(value.trim());
    }

    static boolean isArea(String value) {
        return value.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    static String area(IPalTypeSystemFile file) {
        return PREFIX + file.getDatabaseId() + "-" + file.getFileNumber();
    }

    static List<IPalTypeSystemFile> systemFiles(NdvClient client)
            throws IOException, NdvException, KnowledgeSourceException {
        List<IPalTypeSystemFile> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        IPalTypeSystemFile[] available = client.getSystemFiles();
        if (available != null) {
            for (IPalTypeSystemFile file : available) {
                if (file != null && file.getKind() == IPalTypeSystemFile.FDDM) {
                    if (file.getDatabaseId() <= 0 || file.getFileNumber() <= 0) {
                        throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "FDDM ohne eindeutige Systemdatei");
                    }
                    if (seen.add(area(file))) {
                        result.add(file);
                    }
                }
            }
        }
        if (result.isEmpty()) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "Keine FDDM-Systemdatei verfügbar");
        }
        return result;
    }

    static NdvObjectInfo inFile(NdvObjectInfo object, IPalTypeSystemFile file) throws KnowledgeSourceException {
        if ((object.getDatabaseId() > 0 && object.getDatabaseId() != file.getDatabaseId())
                || (object.getFileNumber() > 0 && object.getFileNumber() != file.getFileNumber())
                || object.getEffectiveName() == null || object.getEffectiveName().isEmpty()
                || object.getEffectiveName().contains("/")) {
            throw new KnowledgeSourceException(Kind.INVALID_RESPONSE, "DDM-Identität stimmt nicht mit der FDDM-Systemdatei überein");
        }
        return new NdvObjectInfo(object.getName(), object.getLongName(), object.getKind(), object.getType(),
                object.getTypeName(), object.getTypeExtension(), object.getSourceSize(), object.getUser(),
                object.getSourceDate(), file.getDatabaseId(), file.getFileNumber());
    }

    static NdvObjectInfo find(NdvClient client, String area, String name)
            throws IOException, NdvException, KnowledgeSourceException {
        for (IPalTypeSystemFile file : systemFiles(client)) {
            if (!area(file).equalsIgnoreCase(area)) {
                continue;
            }
            // List this system file because the server's filter may only match the short name.
            for (NdvObjectInfo object : client.listObjects(file, "", "*", ObjectKind.SOURCE, ObjectType.DDM)) {
                if (object.getType() == ObjectType.DDM && name.equalsIgnoreCase(object.getEffectiveName())) {
                    return inFile(object, file);
                }
            }
        }
        throw new KnowledgeSourceException(Kind.NOT_FOUND, "DDM nicht gefunden: " + area + "/" + name);
    }
}
