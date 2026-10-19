package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Auswahl der Ergebnisliste (aus MainframeMate {@code ResultFilter}/{@code LoadResultsHtmlUseCase}): Favorit,
 * Zeitraum „letzte n Tage“ und die Musterfelder; leere Muster sind {@code *}.
 */
final class BetaViewSelection {

    private final String favoriteId;
    private final String locale;
    private final int daysBack;
    private final String extension;
    private final String form;
    private final String report;
    private final String jobName;

    BetaViewSelection(String favoriteId, String locale, int daysBack, String extension, String form, String report,
                      String jobName) {
        if (favoriteId == null || favoriteId.trim().isEmpty()) {
            throw new IllegalArgumentException("favoriteId must not be blank");
        }
        this.favoriteId = favoriteId.trim();
        this.locale = orDefault(locale, "de");
        this.daysBack = daysBack > 0 ? daysBack : 60;
        this.extension = orDefault(extension, "*");
        this.form = orDefault(form, "*");
        this.report = orDefault(report, "*");
        this.jobName = orDefault(jobName, "*");
    }

    String favoriteId() {
        return favoriteId;
    }

    /** Die Felder für {@code result.action} ohne Struts-Token, wie der Browser sie sendet. */
    Map<String, String> formFields() {
        Map<String, String> form = new LinkedHashMap<String, String>();
        form.put("getDateMask()", "DD.MM.YYYY");
        form.put("favID", favoriteId);
        form.put("locale", locale);
        form.put("focus", "we_id_searchbtn");
        form.put("lastdate", "last");
        form.put("timesel", "sub");
        form.put("lastsel", "individual");
        form.put("lasthoursdaysvalue", Integer.toString(daysBack));
        form.put("timeunit", "days");
        form.put("datefrom", "");
        form.put("timefrom", "");
        form.put("dateto", "");
        form.put("timeto", "");
        form.put("FOLDER", "*");
        form.put("TAB", "*");
        form.put("TITLE", "");
        form.put("FTITLE", "0");
        form.put("RECI", "*");
        form.put("FORM", this.form);
        form.put("EXTENSION", extension);
        form.put("REPORT", report);
        form.put("JOBNAME", jobName);
        for (String status : new String[] {"ONLINE", "LGRNOTE", "LGRXREAD", "ARCHIVE", "PROCESS", "DELETE",
                "LGRSTAT", "RELOAD"}) {
            form.put(status, "");
        }
        return form;
    }

    private static String orDefault(String value, String def) {
        return value == null || value.trim().isEmpty() ? def : value.trim();
    }
}
