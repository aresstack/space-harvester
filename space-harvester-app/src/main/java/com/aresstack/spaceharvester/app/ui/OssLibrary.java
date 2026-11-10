package com.aresstack.spaceharvester.app.ui;

/**
 * Eine ausgelieferte Bibliothek aus dem Lizenzbericht des Builds ({@code oss-licenses.json}, erzeugt von
 * {@code gradle/oss-licenses.gradle}): Maven-Koordinaten, Version, Lizenzname und das Modul, das sie braucht.
 *
 * @param module das erste eigene Modul, das die Bibliothek verwendet; leer, wenn der Bericht keins nennt
 */
record OssLibrary(String group, String artifact, String version, String license, String module) {

    OssLibrary {
        module = module == null ? "" : module;
    }

    String name() {
        return group + ":" + artifact;
    }

    /** Ob Name, Gruppe, Version, Lizenz oder Modul den Filter enthalten (ohne Groß-/Kleinschreibung). */
    boolean matches(String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String needle = filter.strip().toLowerCase(java.util.Locale.ROOT);
        return (name() + " " + version + " " + license + " " + module).toLowerCase(java.util.Locale.ROOT)
                .contains(needle);
    }
}
