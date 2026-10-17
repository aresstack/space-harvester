#!/usr/bin/env python3
"""Prüft, was ein MaltParser-Modell (.mco) aus den Trainingstexten enthält, und gibt nur Zählwerte aus.

Aufruf: mco-audit.py <Modell-ZIP oder .mco> [--treebank <train.conllu>] [--json]

Listet die Dateien im .mco, sucht nach mitgelieferten Korpusdateien und zählt die Einträge jeder Symboltabelle in
symboltables.sym. Mit --treebank wird zusätzlich gezählt, wie viele FORM/FORM-Paare im Train-Teil als direkt
benachbarte Wörter vorkommen und wie viel der Wortfolge der Train-Sätze sich so abdecken lässt. Wortformen oder
Sätze werden nie ausgegeben. Kein Netzwerk, kein Training.
"""
import io
import json
import re
import statistics
import sys
import zipfile
from collections import Counter

CORPUS_SUFFIXES = (".conllu", ".conll", ".conllx", ".tab", ".txt")
TEXT_TABLES = ("FORM", "LEMMA")
SENTINEL = re.compile(r"^#[a-z]+#$")


def open_mco(path):
    with open(path, "rb") as f:
        data = f.read()
    outer = zipfile.ZipFile(io.BytesIO(data))
    names = [n for n in outer.namelist() if n.endswith(".mco")]
    if names:
        return names[0], zipfile.ZipFile(io.BytesIO(outer.read(names[0])))
    return path, outer


def symbol_tables(text):
    """symboltables.sym: Kopf mit einer Zeile je Tabelle, dann je Tabelle Name, Einträge 'Code<TAB>Symbol', Leerzeile."""
    blocks = text.split("\n\n")
    header = [line.split("\t")[1] for line in blocks[0].splitlines() if line.startswith("\t")]
    tables = {}
    for block in blocks[1:]:
        lines = block.splitlines()
        if lines and lines[0] in header:
            tables[lines[0]] = [line.split("\t", 1)[1] for line in lines[1:] if "\t" in line]
    return header, tables


def serialized_strings(data):
    """Druckbare Zeichenfolgen (ASCII und UTF-8) ab sechs Zeichen aus einer serialisierten Datei."""
    return {m.decode("utf-8", "ignore") for m in re.findall(rb"(?:[\x20-\x7e]|[\xc2-\xf4][\x80-\xbf]+){6,}", data)}


def train_sentences(treebank):
    sentences, words = [], []
    with open(treebank, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                if words:
                    sentences.append(words)
                words = []
            elif not line.startswith("#"):
                columns = line.split("\t")
                if columns[0].isdigit():
                    words.append(columns[1])
    if words:
        sentences.append(words)
    return sentences


def coverage(pairs, sentences):
    """Wie viel der Wortfolge des Train-Teils steckt als FORM/FORM-Paare im Modell (Reihenfolge der Paare egal)."""
    known = set(pairs) | {(b, a) for a, b in pairs}
    adjacent = {bigram for words in sentences for bigram in zip(words, words[1:])}
    bigrams = covered = complete = 0
    runs = []
    for words in sentences:
        hits = [bigram in known for bigram in zip(words, words[1:])]
        bigrams += len(hits)
        covered += sum(hits)
        complete += bool(hits) and all(hits)
        best = run = 0
        for hit in hits:
            run = run + 1 if hit else 0
            best = max(best, run)
        runs.append(best + 1)
    runs.sort()
    return {
        "formFormPairsAdjacentInTrain": sum(1 for a, b in pairs if (a, b) in adjacent or (b, a) in adjacent),
        "trainSentences": len(sentences),
        "trainBigrams": bigrams,
        "trainBigramsAsPair": covered,
        "trainSentencesAllBigramsAsPair": complete,
        "longestCoveredRunWords": {"median": runs[len(runs) // 2], "p90": runs[int(len(runs) * 0.9)],
                                   "max": runs[-1]},
    }


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    treebank = args[args.index("--treebank") + 1] if "--treebank" in args else None
    name, mco = open_mco(args[0])
    files = [{"name": i.filename, "bytes": i.file_size} for i in mco.infolist() if not i.is_dir()]
    sym = next(f["name"] for f in files if f["name"].endswith("symboltables.sym"))
    header, tables = symbol_tables(mco.read(sym).decode("utf-8"))
    report = {
        "mco": name,
        "files": files,
        "corpusFiles": [f["name"] for f in files if f["name"].lower().endswith(CORPUS_SUFFIXES)],
        "symbolTables": {t: len(tables.get(t, [])) for t in header},
        "formTables": sorted(t for t in header if "FORM" in t),
    }
    for t in TEXT_TABLES:
        report[f"max{t.title()}Length"] = max((len(s) for s in tables.get(t, [])), default=0)
    entries = [tuple(s.split("~", 1)) for s in tables.get("MERGE2_FORM_FORM", []) if "~" in s]
    # MaltParser trägt für eine leere Stack-Position Platzhalter wie #null# ein; das sind keine Wortformen
    pairs = [p for p in entries if not any(SENTINEL.match(w) for w in p)]
    report["formFormEntries"] = len(entries)
    report["formFormPairs"] = len(pairs)
    partners = Counter(w for pair in pairs for w in pair)
    report["formFormPartners"] = {"median": sorted(partners.values())[len(partners) // 2] if partners else 0,
                                  "mean": round(statistics.mean(partners.values()), 1) if partners else 0}
    forms = {f for f in tables.get("FORM", []) if len(f) >= 6}
    report["formsCheckedInSerializedFiles"] = len(forms)
    report["formsFoundInSerializedFiles"] = {
        f["name"]: len(forms & serialized_strings(mco.read(f["name"])))
        for f in files if f["name"].endswith((".moo", ".map"))}
    if treebank:
        report.update(coverage(pairs, train_sentences(treebank)))
    if "--json" in args:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return
    print(f"Modell: {report['mco']}")
    for f in files:
        print(f"  {f['bytes']:>12}  {f['name']}")
    print(f"Korpusdateien im Archiv: {len(report['corpusFiles'])}")
    print("Symboltabellen (Einträge):")
    for t, n in report["symbolTables"].items():
        print(f"  {n:>8}  {t}")
    print(f"Längste Wortform: {report['maxFormLength']} Zeichen, längstes Lemma: {report['maxLemmaLength']}")
    print(f"MERGE2_FORM_FORM: {report['formFormEntries']} Einträge, davon {report['formFormPairs']} Paare aus zwei "
          f"Wortformen (Rest mit Platzhalter für eine leere Stack-Position)")
    print(f"Partner je Wortform in MERGE2_FORM_FORM: Median {report['formFormPartners']['median']}, "
          f"Mittel {report['formFormPartners']['mean']}")
    for name, found in report["formsFoundInSerializedFiles"].items():
        print(f"Wortformen ab 6 Zeichen als Text in {name}: {found} von {report['formsCheckedInSerializedFiles']}")
    if treebank:
        r = report
        print(f"FORM/FORM-Paare, die im Train-Teil direkt benachbart vorkommen: "
              f"{r['formFormPairsAdjacentInTrain']} von {r['formFormPairs']}")
        print(f"Benachbarte Wortpaare des Train-Teils, die als Paar im Modell stehen: "
              f"{r['trainBigramsAsPair']} von {r['trainBigrams']}")
        print(f"Train-Sätze, deren benachbarte Wortpaare alle im Modell stehen: "
              f"{r['trainSentencesAllBigramsAsPair']} von {r['trainSentences']}")
        runs = r["longestCoveredRunWords"]
        print(f"Längste lückenlos abgedeckte Wortfolge je Satz: Median {runs['median']}, 90 % bis {runs['p90']}, "
              f"höchstens {runs['max']} Wörter")


if __name__ == "__main__":
    main()
