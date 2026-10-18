package com.aresstack.enterpriseai.model.api;

/** Die Quelle eines Modellkatalogs ist nicht erreichbar oder antwortet unlesbar; die Meldung enthält nie Secrets. */
public class ModelCatalogException extends Exception {

    private static final long serialVersionUID = 1L;

    public ModelCatalogException(String message) {
        super(message);
    }

    public ModelCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
