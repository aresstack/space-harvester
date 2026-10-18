package com.aresstack.enterpriseai.astu.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Providerneutrale Generierungsparameter. Jeder nicht gesetzte Wert ({@code null} bzw. leere Stop-Liste)
 * bedeutet "Standard des Providers bzw. des Adapters"; der Adapter sendet ihn dann nicht.
 *
 * <p>Die Werte werden nur auf fachliche Plausibilität geprüft (endlich, nicht negativ, ...). Welche
 * Wertebereiche ein konkreter Server akzeptiert, ist Sache des Adapters.
 */
public final class ChatOptions {

    private final String model;
    private final Double temperature;
    private final Double topP;
    private final Integer topK;
    private final Integer maxTokens;
    private final Double presencePenalty;
    private final Double frequencyPenalty;
    private final List<String> stop;
    private final String endUserId;
    private final String reasoningEffort;

    private ChatOptions(Builder builder) {
        this.model = builder.model;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.topK = builder.topK;
        this.maxTokens = builder.maxTokens;
        this.presencePenalty = builder.presencePenalty;
        this.frequencyPenalty = builder.frequencyPenalty;
        this.stop = Collections.unmodifiableList(new ArrayList<String>(builder.stop));
        this.endUserId = builder.endUserId;
        this.reasoningEffort = builder.reasoningEffort;
    }

    /** @return Optionen ohne gesetzte Werte */
    public static ChatOptions defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return ein Builder mit den Werten dieser Optionen */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.model = model;
        builder.temperature = temperature;
        builder.topP = topP;
        builder.topK = topK;
        builder.maxTokens = maxTokens;
        builder.presencePenalty = presencePenalty;
        builder.frequencyPenalty = frequencyPenalty;
        builder.stop.addAll(stop);
        builder.endUserId = endUserId;
        builder.reasoningEffort = reasoningEffort;
        return builder;
    }

    /**
     * Ergänzt nicht gesetzte Werte dieser Optionen durch die Werte von {@code fallback}.
     * Gesetzte Werte haben Vorrang; eine nicht leere Stop-Liste ersetzt die des Fallbacks vollständig.
     */
    public ChatOptions withFallback(ChatOptions fallback) {
        if (fallback == null) {
            return this;
        }
        Builder builder = toBuilder();
        builder.model = model != null ? model : fallback.model;
        builder.temperature = temperature != null ? temperature : fallback.temperature;
        builder.topP = topP != null ? topP : fallback.topP;
        builder.topK = topK != null ? topK : fallback.topK;
        builder.maxTokens = maxTokens != null ? maxTokens : fallback.maxTokens;
        builder.presencePenalty = presencePenalty != null ? presencePenalty : fallback.presencePenalty;
        builder.frequencyPenalty = frequencyPenalty != null ? frequencyPenalty : fallback.frequencyPenalty;
        if (stop.isEmpty()) {
            builder.stop.addAll(fallback.stop);
        }
        builder.endUserId = endUserId != null ? endUserId : fallback.endUserId;
        builder.reasoningEffort = reasoningEffort != null ? reasoningEffort : fallback.reasoningEffort;
        return builder.build();
    }

    /** @return Modellname oder {@code null} für das im Adapter konfigurierte Standardmodell */
    public String model() {
        return model;
    }

    public Double temperature() {
        return temperature;
    }

    public Double topP() {
        return topP;
    }

    public Integer topK() {
        return topK;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public Double presencePenalty() {
        return presencePenalty;
    }

    public Double frequencyPenalty() {
        return frequencyPenalty;
    }

    /** @return Stop-Sequenzen, unveränderlich, leer wenn keine gesetzt sind */
    public List<String> stop() {
        return stop;
    }

    /** @return eine pseudonyme Kennung des Endnutzers für den Provider oder {@code null} */
    public String endUserId() {
        return endUserId;
    }

    /**
     * @return gewünschter Denkaufwand von Reasoning-Modellen ({@code low}, {@code medium}, {@code high}) oder
     *         {@code null} für den Standard des Modells
     */
    public String reasoningEffort() {
        return reasoningEffort;
    }

    @Override
    public String toString() {
        return "ChatOptions[model=" + model + ", temperature=" + temperature + ", topP=" + topP + ", topK=" + topK
                + ", maxTokens=" + maxTokens + ", presencePenalty=" + presencePenalty
                + ", frequencyPenalty=" + frequencyPenalty + ", stop=" + stop.size()
                + ", endUserId=" + (endUserId == null ? "unset" : "set") + "]";
    }

    public static final class Builder {

        private String model;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private Integer maxTokens;
        private Double presencePenalty;
        private Double frequencyPenalty;
        private final List<String> stop = new ArrayList<String>();
        private String endUserId;
        private String reasoningEffort;

        private Builder() {
        }

        public Builder model(String value) {
            this.model = blankToNull(value);
            return this;
        }

        public Builder temperature(Double value) {
            this.temperature = requireFiniteNonNegative("temperature", value);
            return this;
        }

        public Builder topP(Double value) {
            Double checked = requireFiniteNonNegative("topP", value);
            if (checked != null && checked > 1.0d) {
                throw new IllegalArgumentException("topP must be within [0, 1]");
            }
            this.topP = checked;
            return this;
        }

        public Builder topK(Integer value) {
            this.topK = requirePositive("topK", value);
            return this;
        }

        public Builder maxTokens(Integer value) {
            this.maxTokens = requirePositive("maxTokens", value);
            return this;
        }

        public Builder presencePenalty(Double value) {
            this.presencePenalty = requireFinite("presencePenalty", value);
            return this;
        }

        public Builder frequencyPenalty(Double value) {
            this.frequencyPenalty = requireFinite("frequencyPenalty", value);
            return this;
        }

        public Builder stop(List<String> values) {
            this.stop.clear();
            if (values != null) {
                for (String value : values) {
                    if (value == null || value.isEmpty()) {
                        throw new IllegalArgumentException("stop sequences must not be empty");
                    }
                    this.stop.add(value);
                }
            }
            return this;
        }

        public Builder endUserId(String value) {
            this.endUserId = blankToNull(value);
            return this;
        }

        /** {@code low}, {@code medium}, {@code high} oder {@code null} (Standard des Modells). */
        public Builder reasoningEffort(String value) {
            String effort = blankToNull(value);
            if (effort != null && !effort.matches("low|medium|high")) {
                throw new IllegalArgumentException("reasoningEffort must be low, medium or high");
            }
            this.reasoningEffort = effort;
            return this;
        }

        public ChatOptions build() {
            return new ChatOptions(this);
        }

        private static String blankToNull(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }

        private static Double requireFinite(String name, Double value) {
            if (value != null && (value.isNaN() || value.isInfinite())) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        }

        private static Double requireFiniteNonNegative(String name, Double value) {
            Double checked = requireFinite(name, value);
            if (checked != null && checked < 0.0d) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return checked;
        }

        private static Integer requirePositive(String name, Integer value) {
            if (value != null && value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
