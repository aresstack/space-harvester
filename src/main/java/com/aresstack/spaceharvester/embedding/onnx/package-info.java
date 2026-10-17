/**
 * Lokales Embedding-Modell im Prozess über ONNX Runtime und den Hugging-Face-Tokenizer (#89): Standard ist
 * {@code intfloat/multilingual-e5-small} in einer fest gepinnten Revision. Modelldateien stellt der Betrieb bereit;
 * nichts wird aus dem Netz geladen, und keine Texte verlassen den Prozess.
 */
package com.aresstack.spaceharvester.embedding.onnx;
