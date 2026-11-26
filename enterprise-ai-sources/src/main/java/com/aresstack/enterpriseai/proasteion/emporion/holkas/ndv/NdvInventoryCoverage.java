package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Keep deletion evidence limited to successfully enumerated libraries and explicit name filters. */
final class NdvInventoryCoverage {

    private final List<String> points;

    NdvInventoryCoverage(Collection<String> points) {
        this.points = new ArrayList<String>(points);
    }

    static NdvInventoryCoverage empty() {
        return new NdvInventoryCoverage(Collections.<String>emptyList());
    }

    boolean covers(String nativePath) {
        String path = nativePath.toUpperCase(Locale.ROOT);
        int slash = path.indexOf('/');
        int dot = path.lastIndexOf('.');
        if (slash <= 0 || dot <= slash + 1) {
            return false;
        }
        String library = path.substring(0, slash);
        String name = path.substring(slash + 1, dot);
        for (String value : points) {
            String point = value.trim().toUpperCase(Locale.ROOT);
            if (NdvDdmScope.isStartPoint(point) && NdvDdmScope.isArea(library)) {
                return true;
            }
            int separator = point.indexOf('/');
            String scopeLibrary = separator < 0 ? point : point.substring(0, separator).trim();
            if (library.equals(scopeLibrary) && matches(name,
                    separator < 0 ? "*" : point.substring(separator + 1).trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String name, String filter) {
        if (filter.isEmpty() || "*".equals(filter)) {
            return true;
        }
        if (filter.endsWith("*") && filter.indexOf('*') == filter.length() - 1
                && filter.indexOf('?') < 0 && filter.indexOf('%') < 0) {
            return name.startsWith(filter.substring(0, filter.length() - 1));
        }
        return name.equals(filter);
    }
}