#!/usr/bin/env python3
"""Fasst training.json, evaluation.json und das Manifest eines Laufs als Markdown zusammen.

Aufruf: evaluation-summary.py <Arbeitsverzeichnis> [--release-notes]
Ohne Schalter für die Zusammenfassung des Workflow-Laufs, mit --release-notes für den Text des Entwurfs-Release.
"""
import json
import pathlib
import sys

CORE = ["nsubj", "nsubj:pass", "obj", "obl", "obl:arg", "obl:agent", "nmod", "nmod:poss", "compound:prt", "conj"]


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def main():
    work = pathlib.Path(sys.argv[1])
    notes = "--release-notes" in sys.argv[2:]
    training = load(work / "training.json")
    evaluation = load(work / "evaluation.json")
    manifests = sorted((work / "dist").glob("*.manifest.json"))
    manifest = load(manifests[0]) if manifests else None
    out = []
    if manifest:
        out.append(f"# {manifest['releaseTag']}")
        out.append("")
        approval = manifest["license"]["releaseApproval"]
        if approval != "approved":
            out.append("> **Lizenzfreigabe ausstehend.** Dieser Entwurf darf erst veröffentlicht werden, wenn "
                       "docs/NLP_MODEL_LICENSE.md die Freigabe dokumentiert und das Rezept "
                       "`license.approval=approved` enthält.")
            out.append("")
        tb = manifest["treebank"]
        out.append(f"Deutsches MaltParser-{manifest['maltParserVersion']}-Modell, trainiert auf {tb['name']} "
                   f"{tb['release']} (Commit `{tb['commit']}`), Algorithmus {manifest['training']['algorithm']}/"
                   f"{manifest['training']['learner']}. Eingabe: CoNLL-U, Spalten "
                   f"{', '.join(manifest['inputSchema']['columns'])}, syntaktische Wörter.")
        out.append("")
        out.append(f"Lizenz des Modells: {manifest['license']['model']} (siehe LICENSE-MODEL.md und ATTRIBUTION.md "
                   "im ZIP); nicht MIT.")
        out.append("")
        out.append("Laden: ZIP herunterladen, SHA-256 gegen den im Verbraucher gepinnten Wert prüfen, entpacken, "
                   f"`mcoSha256` aus model-manifest.json prüfen, `{manifest['mcoFile']}` laden.")
        out.append("")
    elif not notes:
        out.append("# MaltParser-Modell")
        out.append("")
        out.append("Kein Manifest: Der Lauf ist vor dem Verpacken abgebrochen.")
        out.append("")
    if training:
        out.append(f"Training: {training['sentences']} Sätze, {training['words']} Wörter, "
                   f"{training['durationSeconds']} s, Heap {training['heap']}, Spitzenspeicher "
                   f"{training.get('peakRssMiB') or 'unbekannt'} MiB, Java {training['javaVersion']}.")
        out.append("")
    if evaluation:
        out.append("| Satz | UAS | LAS | UAS ohne Interpunktion | LAS ohne Interpunktion |")
        out.append("|---|---:|---:|---:|---:|")
        for name, m in evaluation["sets"].items():
            out.append(f"| {name} | {m['uas']:.2f} | {m['las']:.2f} | {m['uasWithoutPunct']:.2f} | "
                       f"{m['lasWithoutPunct']:.2f} |")
        out.append("")
        tagger = evaluation["predictedUpos"]
        out.append(f"Vorhergesagte Wortarten: {tagger['model']}, UPOS-Genauigkeit Dev {tagger['dev']:.2f} %, "
                   f"Test {tagger['test']:.2f} %.")
        out.append("")
        relations = evaluation["sets"]["test.gold"]["relations"]
        predicted = evaluation["sets"]["test.predicted"]["relations"]
        out.append("| Relation (Test) | Gold | Precision | Recall | F1 | F1 mit OpenNLP-Wortarten |")
        out.append("|---|---:|---:|---:|---:|---:|")
        for name in CORE:
            r = relations.get(name)
            p = predicted.get(name) or {"f1": 0}
            if r and r["gold"]:
                out.append(f"| {name} | {r['gold']} | {r['precision']:.1f} | {r['recall']:.1f} | {r['f1']:.1f} | "
                           f"{p['f1']:.1f} |")
        out.append("")
        ref, refp = evaluation["referenceGoldUpos"], evaluation["referencePredictedUpos"]
        out.append(f"Prüfsätze: {ref['passed']}/{ref['checks']} Kanten mit Gold-Wortarten, "
                   f"{refp['passed']}/{refp['checks']} mit vorhergesagten Wortarten.")
        out.append("")
        gate = evaluation["qualityGate"]
        out.append("Qualitätsgrenzen: " + ("bestanden" if gate["passed"] else "VERFEHLT: " + "; ".join(gate["failures"])))
        out.append("")
    print("\n".join(out))


if __name__ == "__main__":
    main()
