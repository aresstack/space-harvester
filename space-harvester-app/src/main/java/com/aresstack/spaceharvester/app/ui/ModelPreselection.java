package com.aresstack.spaceharvester.app.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Kopie von {@code ModelPreselection} aus dem Einrichtungsassistenten des Enterprise AI Client (exedra), unverändert bis
 * auf das Paket; dazu {@link #present(List, String)} für Rollen, die nur ein vorhandenes Vorzugsmodell vorwählen
 * (Vision, Rerank), ohne Ersatzwahl.
 * <p>
 * Picks the model the setup wizard selects by itself: keep a current choice that is still offered, else take the
 * preferred model, else the single offered model containing all match words (by default the last path segment of
 * the preferred id), else the first offered model. A missing preferred model is no error.
 */
final class ModelPreselection {

    private ModelPreselection() {
    }

    /**
     * @param available the offered models as stored ({@code [<catalog>:]<model>}), in catalog order
     * @param current   the current choice, possibly empty
     * @param preferred the preferred model id, possibly empty
     * @return the model to select; {@code current} when nothing is offered
     */
    static String choose(List<String> available, String current, String preferred) {
        return choose(available, current, preferred, Collections.<String>emptyList());
    }

    /** Same as {@link #choose(List, String, String)} with explicit match words for the fallback. */
    static String choose(List<String> available, String current, String preferred, List<String> match) {
        String chosen = current == null ? "" : current.trim();
        if (available == null || available.isEmpty() || available.contains(chosen)) {
            return chosen;
        }
        String wanted = preferred == null ? "" : preferred.trim();
        if (!wanted.isEmpty()) {
            for (String model : available) {
                if (model.equals(wanted) || model.endsWith(":" + wanted)) {
                    return model;
                }
            }
            List<String> words = new ArrayList<String>();
            for (String word : match == null || match.isEmpty()
                    ? Collections.singletonList(wanted.substring(wanted.lastIndexOf('/') + 1)) : match) {
                if (!word.trim().isEmpty()) {
                    words.add(word.trim().toLowerCase(Locale.ROOT));
                }
            }
            String single = null;
            int matches = 0;
            for (String model : available) {
                if (!words.isEmpty() && containsAll(model.toLowerCase(Locale.ROOT), words)) {
                    single = model;
                    matches++;
                }
            }
            if (matches == 1) {
                return single;
            }
        }
        return available.get(0);
    }

    /**
     * Das angebotene Modell, das dem Vorzugsmodell entspricht, oder leer: ohne Rücksicht auf Groß-/Kleinschreibung,
     * mit oder ohne Katalogpräfix ({@code <catalog>:}) und auch ohne Anbieterteil ({@code google/}) auf einer der
     * beiden Seiten. Eine genaue Kennung geht vor einer, die nur ohne Anbieter passt. Keine Ersatzwahl.
     */
    static String present(List<String> available, String preferred) {
        String wanted = preferred == null ? "" : normalized(preferred);
        if (available == null || wanted.isEmpty()) {
            return "";
        }
        for (String model : available) {
            if (model != null && normalized(model).equals(wanted)) {
                return model;
            }
        }
        String wantedTail = tail(wanted);
        for (String model : available) {
            if (model != null && !wantedTail.isEmpty() && tail(normalized(model)).equals(wantedTail)) {
                return model;
            }
        }
        return "";
    }

    /** Kleingeschrieben, ohne Katalogpräfix vor dem ersten „:“, wenn davor kein „/“ steht. */
    private static String normalized(String id) {
        String value = id.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        int slash = value.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) {
            value = value.substring(colon + 1);
        }
        return value;
    }

    private static String tail(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private static boolean containsAll(String model, List<String> words) {
        for (String word : words) {
            if (!model.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
