package com.aresstack.spaceharvester.deck.ftp;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Define the explicit read-only program search boundary and ordered copybook libraries. */
public record CobolAnalysisArea(String home, List<String> syslib) {
    public CobolAnalysisArea {
        home = normalizeDataset(home);
        syslib = FtpProfile.normalizeSyslib(syslib);
    }

    public static Optional<CobolAnalysisArea> configured(String home, List<String> syslib) {
        return home == null || home.isBlank() ? Optional.empty()
                : Optional.of(new CobolAnalysisArea(home, syslib));
    }

    private static String normalizeDataset(String value) {
        String name = value == null ? "" : value.strip();
        if (name.startsWith("'") && name.endsWith("'") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        name = name.toUpperCase(Locale.ROOT);
        if (name.length() > 44 || !name.matches("[A-Z@#$][A-Z0-9@#$-]{0,7}(\\.[A-Z@#$][A-Z0-9@#$-]{0,7})*")) {
            throw new IllegalArgumentException("Ungültiges COBOL-Home: absoluten Dataset-Namen ohne Member eingeben");
        }
        return name;
    }

    public String absoluteHome() {
        return MvsPath.absolute(home);
    }

    /** Match only the selected dataset and descendants separated by a qualifier boundary. */
    public boolean containsProgramDataset(String dataset) {
        try {
            String name = normalizeDataset(dataset);
            return name.equals(home) || name.startsWith(home + ".");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
