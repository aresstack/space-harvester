package com.aresstack.spaceharvester.embedding.onnx;

/**
 * Die gepinnte Fassung von {@code intfloat/multilingual-e5-small} (Entscheidung Angelo, #89): Revision, Dateien und
 * Prüfsummen. Eine neue Revision ist eine bewusste Änderung dieser Werte samt neuer Referenzvektoren
 * ({@code tools/embedding/e5_reference.py}); sie macht über {@link #MODEL_ID} alle gespeicherten Vektoren veraltet.
 */
public final class E5Defaults {

    /** Commit des Modell-Repositorys auf Hugging Face, Stand 2026-04-02. */
    public static final String REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3";
    /** Modell und die ersten zwölf Zeichen der Revision. */
    public static final String MODEL_ID = "intfloat/multilingual-e5-small@614241f622f5";
    /** Relativ zum Modellverzeichnis, wie im Modell-Repository. */
    public static final String MODEL_FILE = "onnx/model.onnx";
    public static final String TOKENIZER_FILE = "tokenizer.json";
    public static final String MODEL_SHA256 = "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665";
    public static final String TOKENIZER_SHA256 = "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39";
    public static final int DIMENSIONS = 384;
    public static final int MAX_TOKENS = 512;
    /** {@code <pad>} des XLM-R-Tokenizers. */
    public static final long PAD_TOKEN_ID = 1;
    public static final String PASSAGE_PREFIX = "passage: ";
    public static final String QUERY_PREFIX = "query: ";

    private E5Defaults() {
    }
}
