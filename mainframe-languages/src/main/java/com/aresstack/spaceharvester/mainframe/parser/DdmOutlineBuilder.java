package com.aresstack.spaceharvester.mainframe.parser;

import com.aresstack.spaceharvester.mainframe.model.JclElement;
import com.aresstack.spaceharvester.mainframe.model.JclElementType;
import com.aresstack.spaceharvester.mainframe.model.JclOutlineModel;
import com.aresstack.spaceharvester.mainframe.parser.DdmParser.DdmDefinition;
import com.aresstack.spaceharvester.mainframe.parser.DdmParser.DdmField;

/**
 * Gliederung einer Natural-DDM, unverändert übernommen aus {@code DdmAnalysisService.buildOutline} von MainframeMate
 * (nur der Gliederungsteil; Abhängigkeitsanalyse und KI-Zusammenfassung gehören nicht zum Editor).
 */
public class DdmOutlineBuilder {

    private final DdmParser parser = new DdmParser();

    /** Endung {@code .nsd} wie in MainframeMate. */
    public static boolean isDdmFile(String path) {
        return path != null && path.toLowerCase().endsWith(".nsd");
    }

    /** "MYLIB/MYDDM.NSD" → "MYDDM". */
    public static String extractDdmName(String path) {
        if (path == null) return null;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String filename = (slash >= 0) ? path.substring(slash + 1) : path;
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot).toUpperCase() : filename.toUpperCase();
    }

    public JclOutlineModel parse(String source, String sourceName) {
        JclOutlineModel model = new JclOutlineModel();
        model.setLanguage(JclOutlineModel.Language.DDM);
        model.setSourceName(sourceName);

        if (source == null || source.isEmpty()) return model;

        String fallbackName = extractDdmName(sourceName);
        DdmDefinition ddm = parser.parse(source, fallbackName);
        if (ddm == null) return model;

        String[] lines = source.split("\\r?\\n");
        model.setTotalLines(lines.length);

        // ── Header element ──
        String headerText = ddm.getName() + "  (DB:" + ddm.getDbId()
                + " FILE:" + ddm.getFileNumber() + ")";
        if (ddm.getDefaultSequence() != null && !ddm.getDefaultSequence().isEmpty()) {
            headerText += "  SEQ:" + ddm.getDefaultSequence();
        }
        JclElement headerElem = new JclElement(JclElementType.DDM_HEADER, headerText, 1,
                "DB: " + ddm.getDbId() + " FILE: " + ddm.getFileNumber() + " - " + ddm.getName());
        headerElem.addParameter("DDM", ddm.getName());
        headerElem.addParameter("DB", String.valueOf(ddm.getDbId()));
        headerElem.addParameter("FILE", String.valueOf(ddm.getFileNumber()));
        if (ddm.getDefaultSequence() != null && !ddm.getDefaultSequence().isEmpty()) {
            headerElem.addParameter("DEFAULT_SEQ", ddm.getDefaultSequence());
        }
        model.addElement(headerElem);

        // ── Field elements ──
        // Track the current group for nesting
        JclElement currentGroup = null;
        int searchFrom = findFieldStartLine(lines); // 0-based index to start searching

        for (int i = 0; i < ddm.getFields().size(); i++) {
            DdmField field = ddm.getFields().get(i);
            int lineIndex = findFieldLine(lines, field, searchFrom);
            int lineNum = lineIndex + 1; // convert to 1-based
            searchFrom = lineIndex + 1;  // next field must be after this one

            JclElementType type;
            if (field.isSuperdescriptor()) {
                type = JclElementType.DDM_SUPERDESCRIPTOR;
            } else if (field.isDescriptor()) {
                type = JclElementType.DDM_DESCRIPTOR;
            } else if (field.isPeriodicGroup() || field.isMultipleValue()) {
                type = JclElementType.DDM_GROUP;
            } else if (field.isGroup()) {
                type = JclElementType.DDM_GROUP;
            } else {
                type = JclElementType.DDM_FIELD;
            }

            // Build display name: "AB PERSONNEL-ID  A8"
            String displayName = field.getShortName() + " " + field.getLongName();
            if (field.getFormat() != null && !field.getFormat().isEmpty()) {
                displayName += "  " + field.getFormatSpec();
            }

            JclElement elem = new JclElement(type, displayName, lineNum,
                    buildFieldRawText(field, ddm.getDefaultSequence()));

            elem.addParameter("SHORT_NAME", field.getShortName());
            elem.addParameter("LONG_NAME", field.getLongName());
            elem.addParameter("LEVEL", String.valueOf(field.getLevel()));
            if (field.getFormat() != null && !field.getFormat().isEmpty()) {
                elem.addParameter("FORMAT", field.getFormatSpec());
            }
            if (field.isDescriptor() || field.isSuperdescriptor()) {
                elem.addParameter("KEY", field.getKeyLabel(ddm.getDefaultSequence()));
            }
            String keyLabel = field.getKeyLabel(ddm.getDefaultSequence());
            if (!keyLabel.isEmpty()) {
                elem.addParameter("KEY_TYPE", keyLabel);
            }

            // Nesting: Level 1 fields are top-level, Level 2+ are children of groups
            if (field.getLevel() == 1 || field.getLevel() == 0) {
                model.addElement(elem);
                if (field.isPeriodicGroup() || field.isMultipleValue() || field.isGroup()) {
                    currentGroup = elem;
                } else {
                    currentGroup = null;
                }
            } else if (currentGroup != null) {
                currentGroup.addChild(elem);
            } else {
                model.addElement(elem);
            }
        }

        return model;
    }

    /**
     * Find the 0-based line index where field definitions start (after the separator line).
     */
    private int findFieldStartLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("-") && line.contains("-") && line.length() > 5) {
                return i + 1; // fields start right after the separator
            }
        }
        return 3; // default: after header
    }

    /**
     * Find the 0-based line index where a specific field appears in the source.
     * Searches forward from startIndex for a line containing the field's long name
     * (and optionally short name). Falls back to startIndex if not found.
     */
    private int findFieldLine(String[] lines, DdmField field, int startIndex) {
        String longName = field.getLongName();
        String shortName = field.getShortName();

        // Primary: match long name (unique within a DDM, e.g. "PERSONNEL-ID")
        if (longName != null && !longName.isEmpty()) {
            for (int i = startIndex; i < lines.length; i++) {
                if (lines[i].contains(longName)) {
                    return i;
                }
            }
        }

        // Fallback: match short name (2-char code like "AA", "AB")
        if (shortName != null && !shortName.isEmpty()) {
            for (int i = startIndex; i < lines.length; i++) {
                if (lines[i].contains(shortName)) {
                    return i;
                }
            }
        }

        return startIndex; // ultimate fallback
    }

    private String buildFieldRawText(DdmField field, String defaultSeq) {
        StringBuilder sb = new StringBuilder();
        sb.append(field.getShortName()).append(" ").append(field.getLongName());
        if (field.getFormat() != null && !field.getFormat().isEmpty()) {
            sb.append("  ").append(field.getFormatSpec());
        }
        String key = field.getKeyLabel(defaultSeq);
        if (!key.isEmpty()) {
            sb.append("  [").append(key).append("]");
        }
        return sb.toString();
    }
}
