package com.aresstack.spaceharvester.application.export;

import java.util.Objects;

/**
 * Ein JSON-Paket lässt sich nicht importieren: kein Paket, zu alt (vor {@code json-package/3}, ohne Rohdaten),
 * beschädigt oder zu groß. Die Meldung nennt Dateien im Paket, nie Inhalte.
 */
public class ExportPackageException extends RuntimeException {

    /** Keine ZIP-Datei oder ohne {@code manifest.json}. */
    public static final String NOT_A_PACKAGE = "import-not-a-package";
    /** Ein Paket vor {@code json-package/3}: ohne Rohdaten, nicht verlustfrei importierbar. */
    public static final String UNSUPPORTED = "import-unsupported";
    /** Eine Datei fehlt, passt nicht zu ihrer Prüfsumme oder ist unlesbar. */
    public static final String DAMAGED = "import-damaged";
    /** Eine Datei überschreitet die Grenze. */
    public static final String TOO_LARGE = "import-too-large";

    private final String code;

    public ExportPackageException(String code, String message) {
        this(code, message, null);
    }

    public ExportPackageException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }
}
