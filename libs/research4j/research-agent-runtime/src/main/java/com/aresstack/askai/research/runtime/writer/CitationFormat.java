package com.aresstack.askai.research.runtime.writer;

import java.util.Locale;

/**
 * How the section writer sees its evidence and cites it. Every format ends in the same {@code [n]} markers, so the
 * citation check, the claim check and the book report stay one path; only the prompt and the reading of the answer
 * differ.
 */
public enum CitationFormat {

    /** Passages as {@code [n] """text"""}, cited as {@code [n]}: the format of every general chat model. */
    NUMBERED("numbered"),

    /**
     * Every sentence of a passage wrapped in an 8-character hex tag ({@code <a1b2c3d4>Satz.</a1b2c3d4>}) inside a
     * {@code <text>} block, cited as {@code [<a1b2c3d4>]} right after the claim: the format ellamind SUI-1 was trained
     * on (model card of {@code ellamind/sui-1-24b}). {@link SentenceTags} maps each tag back to its passage number.
     */
    SENTENCE_TAGS("sentence-tags");

    /** The setting value that lets the writer model choose ({@link #forModel}). */
    public static final String AUTO = "auto";

    /** The name stem of the SUI-1 models (Ollama names such as {@code sui-1-24b}, {@code hf.co/.../sui-1-24b-gguf}). */
    public static final String SUI_MODEL_STEM = "sui-1";

    private final String id;

    CitationFormat(String id) {
        this.id = id;
    }

    /** The setting value. */
    public String id() {
        return id;
    }

    /** The format a writer model was trained for: sentence tags for SUI-1, numbered passages for every other model. */
    public static CitationFormat forModel(String model) {
        String name = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return name.contains(SUI_MODEL_STEM) ? SENTENCE_TAGS : NUMBERED;
    }

    /**
     * The format of a setting value: {@link #AUTO} (or an unknown or empty value) follows the model, a format id
     * forces that format.
     */
    public static CitationFormat resolve(String setting, String model) {
        String value = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        for (CitationFormat format : values()) {
            if (format.id.equals(value)) {
                return format;
            }
        }
        return forModel(model);
    }
}
