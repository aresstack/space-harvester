#!/usr/bin/env python3
"""Bestandsaufnahme gespeicherter Confluence-REST-Antworten für den Spike #7, ohne Seiteninhalte auszugeben.

Aufruf:  python3 analyze.py <verzeichnis>  [> bericht.md]

Das Verzeichnis enthält im Browser oder per curl gespeicherte Antworten (Dateiendung .json):
  <id>.a.json, <id>.b.json      zweimal derselbe Abruf von /rest/api/content/<id>?expand=…  (Reproduzierbarkeit)
  <id>.attachments.json         /rest/api/content/<id>/child/attachment?expand=…            (optional)
  <id>.comments.json            /rest/api/content/<id>/child/comment?expand=…               (optional)
  weitere *.json                beliebige weitere Antworten; sie gehen nur in die Feldübersicht ein

Der Bericht enthält ausschließlich Strukturangaben: Feldpfade, Größen, Anzahlen, Makro- und Parameternamen,
Referenzarten. Keine Titel, Texte, Dateinamen, Benutzernamen oder URLs. Er kann daher weitergegeben werden,
ohne die Seiten selbst weiterzugeben. Nur die Standardbibliothek wird benutzt.
"""

import collections
import json
import pathlib
import re
import sys

# Elemente im Storage-Format, deren Vorkommen gezählt wird (Namensraum-Präfixe ac: und ri:).
STORAGE_ELEMENT = re.compile(r"<((?:ac|ri):[A-Za-z0-9-]+)\b")
MACRO = re.compile(r"<ac:(?:structured-macro|macro)\b[^>]*\bac:name=\"([^\"]+)\"")
MACRO_BLOCK = re.compile(
    r"<ac:(?:structured-macro|macro)\b[^>]*\bac:name=\"([^\"]+)\"[^>]*>(.*?)</ac:(?:structured-macro|macro)>",
    re.S)
PARAMETER = re.compile(r"<ac:parameter\b[^>]*\bac:name=\"([^\"]*)\"")
ATTACHMENT_REF = re.compile(r"<ri:attachment\b[^>]*\bri:filename=\"([^\"]+)\"")


def leaf_paths(value, prefix=""):
    """Alle Pfade zu Blättern mit Wert; Listenindizes als []."""
    if isinstance(value, dict):
        for key, child in value.items():
            yield from leaf_paths(child, f"{prefix}.{key}" if prefix else key)
    elif isinstance(value, list):
        for child in value:
            yield from leaf_paths(child, f"{prefix}[]")
    else:
        yield prefix, value


def differing_paths(a, b, prefix=""):
    """Pfade, an denen sich zwei JSON-Werte unterscheiden (ohne die Werte selbst)."""
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            path = f"{prefix}.{key}" if prefix else key
            if key not in a or key not in b:
                yield path + " (nur in einer Antwort)"
            else:
                yield from differing_paths(a[key], b[key], path)
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            yield f"{prefix}[] (Länge {len(a)} ≠ {len(b)})"
        else:
            for x, y in zip(a, b):
                yield from differing_paths(x, y, prefix + "[]")
    elif a != b:
        yield prefix


def collection_state(collection):
    """Angaben zu einer eingebetteten, paginierten Sammlung wie metadata.labels."""
    if not isinstance(collection, dict) or "results" not in collection:
        return None
    links = collection.get("_links") or {}
    return {
        "size": collection.get("size"),
        "limit": collection.get("limit"),
        "truncated": "next" in links,
    }


def storage_inventory(storage, macros, parameters, elements):
    for name in MACRO.findall(storage):
        macros[name] += 1
    for name, inner in MACRO_BLOCK.findall(storage):
        for parameter in PARAMETER.findall(inner):
            parameters[(name, parameter or "(ohne Namen)")] += 1
    for element in STORAGE_ELEMENT.findall(storage):
        elements[element] += 1


def load(path):
    raw = path.read_bytes()
    return raw, json.loads(raw.decode("utf-8"))


def main(directory):
    files = sorted(directory.glob("*.json"))
    if not files:
        sys.exit(f"Keine .json-Dateien in {directory}")

    macros, parameters, elements = collections.Counter(), collections.Counter(), collections.Counter()
    field_paths = collections.Counter()
    expandable = collections.Counter()
    pages, collections_seen, sizes = [], [], []
    by_id = collections.defaultdict(dict)

    for path in files:
        raw, data = load(path)
        sizes.append((path.name.split(".")[0], path.name.split(".", 1)[1], len(raw)))
        for leaf, _ in leaf_paths(data):
            field_paths[re.sub(r"\.\d+\b", ".<n>", leaf)] += 1
        parts = path.name.split(".")
        if len(parts) == 3 and parts[1] in ("a", "b"):
            by_id[parts[0]][parts[1]] = (raw, data)
        if isinstance(data, dict) and data.get("type") in ("page", "blogpost") and parts[1] != "b":
            pages.append((parts[0], data))
            for key in (data.get("_expandable") or {}):
                expandable[key] += 1
            storage = ((data.get("body") or {}).get("storage") or {}).get("value")
            if storage:
                storage_inventory(storage, macros, parameters, elements)
            labels = collection_state((data.get("metadata") or {}).get("labels"))
            if labels:
                collections_seen.append(("metadata.labels", parts[0], labels))
            # Anhänge im Storage gegen die Anhangsliste derselben Seite abgleichen (nur Anzahlen).
            attachments_file = directory / f"{parts[0]}.attachments.json"
            if storage and attachments_file.exists():
                _, attachments = load(attachments_file)
                listed = {a.get("title") for a in attachments.get("results", [])}
                referenced = set(ATTACHMENT_REF.findall(storage))
                collections_seen.append(("child/attachment", parts[0], {
                    "size": attachments.get("size"), "limit": attachments.get("limit"),
                    "truncated": "next" in (attachments.get("_links") or {}),
                    "im Storage referenziert": len(referenced),
                    "referenziert, aber nicht gelistet": len(referenced - listed),
                    "gelistet, aber nicht im Storage": len(listed - referenced),
                }))

    out = print
    out("# Bestandsaufnahme Confluence-REST-Antworten (#7)\n")
    out(f"{len(files)} Dateien, {len(pages)} Seiten.\n")

    out("## Größen\n")
    out("| Seite | Datei | Bytes |\n|---|---|---|")
    for page_id, kind, size in sizes:
        out(f"| {page_id} | {kind} | {size} |")

    out("\n## Reproduzierbarkeit (gleicher Abruf zweimal: <id>.a.json / <id>.b.json)\n")
    pairs = {k: v for k, v in by_id.items() if "a" in v and "b" in v}
    if not pairs:
        out("Keine Paare vorhanden.")
    for page_id, pair in sorted(pairs.items()):
        (raw_a, a), (raw_b, b) = pair["a"], pair["b"]
        if raw_a == raw_b:
            out(f"- {page_id}: bytegleich")
        else:
            diffs = sorted(set(differing_paths(a, b)))
            out(f"- {page_id}: unterschiedlich ({len(raw_a)} / {len(raw_b)} Bytes); abweichende Felder: "
                + (", ".join(f"`{d}`" for d in diffs) if diffs else "keine (nur Serialisierung/Reihenfolge)"))

    out("\n## Eingebettete Sammlungen (Paginierung)\n")
    if not collections_seen:
        out("Keine gefunden.")
    for kind, page_id, state in collections_seen:
        out(f"- {page_id} `{kind}`: " + ", ".join(f"{k}={v}" for k, v in state.items()))

    out("\n## Nicht expandierte Felder (`_expandable`) je Seite\n")
    for key, count in sorted(expandable.items()):
        out(f"- `{key}`: {count}")

    out("\n## Makros im Storage-Format\n")
    out("| Makro | Vorkommen |\n|---|---|")
    for name, count in macros.most_common():
        out(f"| {name} | {count} |")

    out("\n## Makroparameter (nur Namen)\n")
    out("| Makro | Parameter | Vorkommen |\n|---|---|---|")
    for (macro, parameter), count in sorted(parameters.items()):
        out(f"| {macro} | {parameter} | {count} |")

    out("\n## Storage-Elemente und Referenzarten (ac:/ri:)\n")
    out("| Element | Vorkommen |\n|---|---|")
    for name, count in elements.most_common():
        out(f"| {name} | {count} |")

    out("\n## Feldpfade (über alle Dateien)\n")
    for leaf, count in sorted(field_paths.items()):
        out(f"- `{leaf}` ({count})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))
