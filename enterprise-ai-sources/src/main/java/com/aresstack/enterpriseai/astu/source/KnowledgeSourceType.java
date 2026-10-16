package com.aresstack.enterpriseai.astu.source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Beschreibung eines Quelltyps (MediaWiki, Confluence, lokale Dateien, später SharePoint, Mail, FTP ...), die ein
 * Adapter über den Quellen-Port anbietet. Der Dialog „+ Quelle“ listet die Typen und baut das Formular aus den
 * {@link #fields() Feldern}; Oberfläche und Use Cases verzweigen nie nach dem Typ.
 *
 * <p>{@link #id()} ist die Serialisierung in der Konfiguration ({@code source.<n>.type}), {@link #scheme()}
 * das Ressourcenschema nach corenth ({@code ResourceScheme}: {@code wiki}, {@code confluence}, {@code file} ...),
 * das der Dialog unter „Erweitert“ als {@link #pathPrefix() Pfadpräfix} zeigt. Die Identität einer Quelle ergibt
 * sich aus Typ und kanonischem Endpoint ({@link #addressFor}), nie aus einer Benutzerangabe.
 * Wie in corenth ist der Typ ein offener Wert, kein Enum: neue Adapter bringen ihren Typ mit, ohne den Kern zu
 * ändern.
 */
public final class KnowledgeSourceType {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,39}");

    private final String id;
    private final String scheme;
    private final String displayName;
    private final String description;
    private final String idPrefix;
    private final String summaryKey;
    private final String titleKey;
    private final String partitionsLabel;
    private final boolean fullIndexing;
    private final List<SourceSettingField> fields;

    private KnowledgeSourceType(Builder b) {
        this.id = b.id;
        this.scheme = b.scheme;
        this.displayName = b.displayName;
        this.description = b.description;
        this.idPrefix = b.idPrefix;
        this.summaryKey = b.summaryKey;
        this.titleKey = b.titleKey;
        this.partitionsLabel = b.partitionsLabel;
        this.fullIndexing = b.fullIndexing;
        this.fields = Collections.unmodifiableList(new ArrayList<SourceSettingField>(b.fields));
    }

    /**
     * @param id          Typ in der Konfiguration, z. B. {@code mediawiki} (Kleinbuchstaben, Ziffern, Strich)
     * @param scheme      Ressourcenschema der Quelle, z. B. {@code wiki}
     * @param displayName Name für Menschen, z. B. „MediaWiki“
     */
    public static Builder builder(String id, String scheme, String displayName) {
        return new Builder(id, scheme, displayName);
    }

    public String id() {
        return id;
    }

    /** Ressourcenschema (corenth {@code ResourceScheme}) der Ressourcen dieser Quelle. */
    public String scheme() {
        return scheme;
    }

    public String displayName() {
        return displayName;
    }

    /** Ein Satz, was die Quelle liest; leer, wenn keiner. */
    public String description() {
        return description;
    }

    /**
     * Das Pfadpräfix der Ressourcen dieses Typs zur Anzeige, immer mit {@code :} ({@code confluence:},
     * {@code ndv:} ...). Technische Information, keine Benutzereinstellung, wird nicht gespeichert.
     */
    public String pathPrefix() {
        return scheme + ":";
    }

    /**
     * Die kanonische Adresse einer Quelle dieses Typs am Locator ({@link SourceLocators}), als corenth-Bookmark
     * {@code <scheme>://<locator>}: {@code confluence://cf.intern/confluence}, {@code ndv://host.example:2700},
     * {@code file:///C:/Wissen}. Sie ist die Identität der Quelle (Konfiguration, Häkchen, Bereichsauswahl,
     * Indexzuordnung), deterministisch und nie vom Benutzer vergeben.
     *
     * @return die Adresse oder {@code ""}, wenn der Locator leer ist
     */
    public String addressFor(String locator) {
        if (locator == null || locator.trim().isEmpty()) {
            return "";
        }
        return scheme + "://" + locator.trim();
    }

    /** Früherer Vorschlag für frei vergebene IDs; nur noch Suchbegriff für passende KeePass-Einträge. */
    public String idPrefix() {
        return idPrefix;
    }

    /** Schlüssel des Felds, das die Quellenzeile unter dem Namen zeigt (Adresse oder Verzeichnis). */
    public String summaryKey() {
        return summaryKey;
    }

    /** Schlüssel des Felds mit dem Titel, den die Quellenzeile statt der ID zeigt; leer, wenn keiner. */
    public String titleKey() {
        return titleKey;
    }

    /**
     * Name der Bereiche, in die sich die Quelle gliedert und die der Benutzer an- und abhaken kann (Confluence:
     * „Spaces“); leer, wenn die Quelle keine hat. Die Bereiche selbst liefert der Quellen-Port.
     */
    public String partitionsLabel() {
        return partitionsLabel;
    }

    /** Die Felder in Anzeigereihenfolge. */
    /**
     * {@code true}: Die Quelle bietet „Alles indexieren“ auf Knopfdruck (begrenzter Bestand wie NDV-Bibliotheken
     * in MainframeMate). Sonst wächst ihr Index nur mit der Suche; automatisch indexiert wird nie.
     */
    public boolean fullIndexing() {
        return fullIndexing;
    }

    public List<SourceSettingField> fields() {
        return fields;
    }

    /** Das Feld zum Schlüssel oder {@code null}. */
    public SourceSettingField field(String key) {
        for (SourceSettingField field : fields) {
            if (field.key().equals(key)) {
                return field;
            }
        }
        return null;
    }

    /** Eine neue Quelle dieses Typs mit den Vorgaben der Felder. */
    public SourceSettings defaults() {
        SourceSettings settings = SourceSettings.empty();
        for (SourceSettingField field : fields) {
            if (!field.defaultValue().isEmpty()) {
                settings = settings.with(field.key(), field.defaultValue());
            }
        }
        return settings;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof KnowledgeSourceType && id.equals(((KnowledgeSourceType) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "KnowledgeSourceType[" + id + ", " + scheme + "]";
    }

    public static final class Builder {
        private final String id;
        private final String scheme;
        private final String displayName;
        private String description = "";
        private String idPrefix;
        private String summaryKey = "";
        private String titleKey = "";
        private String partitionsLabel = "";
        private boolean fullIndexing;
        private final List<SourceSettingField> fields = new ArrayList<SourceSettingField>();

        private Builder(String id, String scheme, String displayName) {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Typ-ID muss dem Muster [a-z][a-z0-9-]{0,39} entsprechen");
            }
            if (scheme == null || !ID.matcher(scheme).matches()) {
                throw new IllegalArgumentException("Schema muss dem Muster [a-z][a-z0-9-]{0,39} entsprechen");
            }
            if (displayName == null || displayName.trim().isEmpty()) {
                throw new IllegalArgumentException("displayName must be set");
            }
            this.id = id;
            this.scheme = scheme;
            this.displayName = displayName.trim();
            this.idPrefix = id;
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value.trim();
            return this;
        }

        public Builder idPrefix(String value) {
            if (value == null || !ID.matcher(value).matches()) {
                throw new IllegalArgumentException("ID-Vorschlag muss dem Muster [a-z][a-z0-9-]{0,39} entsprechen");
            }
            this.idPrefix = value;
            return this;
        }

        public Builder summaryKey(String value) {
            this.summaryKey = value == null ? "" : value.trim();
            return this;
        }

        public Builder titleKey(String value) {
            this.titleKey = value == null ? "" : value.trim();
            return this;
        }

        public Builder partitionsLabel(String value) {
            this.partitionsLabel = value == null ? "" : value.trim();
            return this;
        }

        /** Vollindexierung auf Knopfdruck anbieten (siehe {@link KnowledgeSourceType#fullIndexing()}). */
        public Builder fullIndexing(boolean value) {
            this.fullIndexing = value;
            return this;
        }

        public Builder field(SourceSettingField value) {
            if (value == null) {
                throw new IllegalArgumentException("field must not be null");
            }
            this.fields.add(value);
            return this;
        }

        public KnowledgeSourceType build() {
            Set<String> keys = new LinkedHashSet<String>();
            for (SourceSettingField field : fields) {
                if (!keys.add(field.key())) {
                    throw new IllegalArgumentException("Feld doppelt: " + field.key());
                }
            }
            return new KnowledgeSourceType(this);
        }
    }
}
