#!/usr/bin/env python3
"""Referenzvektoren für multilingual-e5-small (#89) mit der PyTorch-Implementierung der Modellkarte.

Unabhängig vom ONNX-Weg des Harvesters: Tokenizer und Gewichte aus transformers, Mean-Pooling über die
Attention-Maske, L2-Normierung, Präfixe "query: " und "passage: " wie in der Modellkarte. Ergebnis als JSON auf
stdout; liegt als space-harvester-core/src/test/resources/embedding/multilingual-e5-small/reference.json im Repository.

    python3 tools/embedding/e5_reference.py <modellverzeichnis> > reference.json

Das Modellverzeichnis ist die gepinnte Revision (docs/EMBEDDINGS.md) mit config.json, Tokenizer-Dateien und
model.safetensors; der Workflow embedding-model.yml erzeugt die Datei mit regenerate=true.
"""

import json
import sys

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer

# Deutsch und Englisch, kurz und lang, mit Sonderzeichen; der lange Text überschreitet 512 Token.
TEXTS = [
    "query: Wie läuft der nächtliche Betrieb?",
    "query: How does the nightly operation work?",
    "passage: Der nächtliche Betrieb startet um 2 Uhr mit dem Abgleich aller Spaces; Fehler meldet das Team Betrieb.",
    "passage: The nightly job starts at 2 a.m. and synchronises every space; the operations team handles failures.",
    "passage: Größe, Maße & Gewicht: 3 × 4 m² – Übersicht für Ärzte (ß, é, ç).",
    "passage: " + " ".join("Abschnitt %d beschreibt die Freigabe von Seiten im Space." % i for i in range(120)),
]


def main(model_dir):
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModel.from_pretrained(model_dir)
    model.eval()
    batch = tokenizer(TEXTS, max_length=512, padding=True, truncation=True, return_tensors="pt")
    with torch.no_grad():
        hidden = model(**batch).last_hidden_state
    mask = batch["attention_mask"].unsqueeze(-1).to(hidden.dtype)
    pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1)
    vectors = F.normalize(pooled, p=2, dim=1)
    out = {
        "model": "intfloat/multilingual-e5-small",
        "pooling": "mean",
        "normalized": True,
        "maxTokens": 512,
        "cases": [{"text": t, "tokens": int(batch["attention_mask"][i].sum()),
                   "inputIds": batch["input_ids"][i][: int(batch["attention_mask"][i].sum())].tolist(),
                   "vector": [round(float(v), 6) for v in vectors[i]]}
                  for i, t in enumerate(TEXTS)],
    }
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv[1])
