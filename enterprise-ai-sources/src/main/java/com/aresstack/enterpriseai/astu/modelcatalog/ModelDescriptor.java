package com.aresstack.enterpriseai.astu.modelcatalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ein Modell, wie ein Katalog es meldet: Katalog, Kennung, Anzeigename, Fähigkeiten, Ein- und Ausgabemodalitäten,
 * Tool-Calling, Reasoning und Kontextlänge. Die Kategorien leitet {@link ModelClassification} daraus ab; eine
 * Namensheuristik gibt es nicht.
 */
public final class ModelDescriptor {

    private final String catalogId;
    private final String catalogName;
    private final String modelId;
    private final String displayName;
    private final List<String> capabilities;
    private final List<String> inputModalities;
    private final List<String> outputModalities;
    private final boolean toolCalling;
    private final boolean reasoning;
    private final int contextLength;
    private final Set<ModelCategory> categories;

    private ModelDescriptor(Builder b) {
        this.catalogId = b.catalogId;
        this.catalogName = b.catalogName == null || b.catalogName.trim().isEmpty() ? b.catalogId : b.catalogName.trim();
        this.modelId = b.modelId;
        this.displayName = b.displayName == null || b.displayName.trim().isEmpty() ? b.modelId : b.displayName.trim();
        this.capabilities = normalized(b.capabilities);
        this.inputModalities = normalized(b.inputModalities);
        this.outputModalities = normalized(b.outputModalities);
        this.toolCalling = b.toolCalling;
        this.reasoning = b.reasoning;
        this.contextLength = Math.max(0, b.contextLength);
        this.categories = Collections.unmodifiableSet(
                ModelClassification.categoriesOf(capabilities, inputModalities, outputModalities));
    }

    public static Builder builder(String catalogId, String modelId) {
        return new Builder(catalogId, modelId);
    }

    public String catalogId() {
        return catalogId;
    }

    /** Anzeigename des Katalogs (z. B. „KIPITZ“ oder „Lokal“). */
    public String catalogName() {
        return catalogName;
    }

    public String modelId() {
        return modelId;
    }

    public String displayName() {
        return displayName;
    }

    /** Fähigkeiten in Kleinschreibung, wie gemeldet (z. B. {@code completion}, {@code embeddings}). */
    public List<String> capabilities() {
        return capabilities;
    }

    public List<String> inputModalities() {
        return inputModalities;
    }

    public List<String> outputModalities() {
        return outputModalities;
    }

    public boolean toolCalling() {
        return toolCalling;
    }

    public boolean reasoning() {
        return reasoning;
    }

    /** Kontextlänge in Token; 0 = unbekannt. */
    public int contextLength() {
        return contextLength;
    }

    public Set<ModelCategory> categories() {
        return categories;
    }

    public boolean supports(ModelCategory category) {
        return categories.contains(category);
    }

    public ModelReference reference() {
        return new ModelReference(catalogId, modelId);
    }

    private static List<String> normalized(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    String normalized = value.trim().toLowerCase(Locale.ROOT);
                    if (!result.contains(normalized)) {
                        result.add(normalized);
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public String toString() {
        return "ModelDescriptor[" + catalogId + ":" + modelId + ", " + categories + "]";
    }

    public static final class Builder {

        private final String catalogId;
        private final String modelId;
        private String catalogName;
        private String displayName;
        private List<String> capabilities = Collections.emptyList();
        private List<String> inputModalities = Collections.emptyList();
        private List<String> outputModalities = Collections.emptyList();
        private boolean toolCalling;
        private boolean reasoning;
        private int contextLength;

        private Builder(String catalogId, String modelId) {
            ModelReference checked = new ModelReference(catalogId, modelId);
            this.catalogId = checked.catalogId();
            this.modelId = checked.modelId();
        }

        public Builder catalogName(String value) {
            this.catalogName = value;
            return this;
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder capabilities(List<String> value) {
            this.capabilities = value;
            return this;
        }

        public Builder inputModalities(List<String> value) {
            this.inputModalities = value;
            return this;
        }

        public Builder outputModalities(List<String> value) {
            this.outputModalities = value;
            return this;
        }

        public Builder toolCalling(boolean value) {
            this.toolCalling = value;
            return this;
        }

        public Builder reasoning(boolean value) {
            this.reasoning = value;
            return this;
        }

        public Builder contextLength(int value) {
            this.contextLength = value;
            return this;
        }

        public ModelDescriptor build() {
            return new ModelDescriptor(this);
        }
    }
}
