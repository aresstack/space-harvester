#!/usr/bin/env python3
"""Auszug aus der OpenAPI-Referenz von Confluence Data Center für die Vertragstests der Fassade (#31).

Liest die von Atlassian veröffentlichte OpenAPI-Datei und schreibt die GET-Operationen der Endpunkte, die die
Fassade anbietet, samt aller über $ref erreichbaren Schemas kanonisch sortiert als JSON. Beschreibungstexte fallen
weg, Parameter, Statuscodes, Schemas und Beispiele bleiben. Der Auszug liegt je Version unter
src/test/resources/confluence-contract/<version>/openapi-extract.json.

    python3 tools/confluence-contract/extract.py <openapi.json> > openapi-extract.json

Nur Python 3, keine weiteren Pakete.
"""

import json
import sys

# Die Endpunkte der Fassade, in der Schreibweise der Referenz.
PATHS = ("/rest/api/content", "/rest/api/content/{id}", "/rest/api/content/{id}/child/page")
DROP = {"description", "summary", "externalDocs", "x-atlassian-connect-scope", "x-experimental", "tags",
        "operationId", "deprecated"}


def strip(node):
    if isinstance(node, dict):
        return {k: strip(v) for k, v in node.items() if k not in DROP}
    if isinstance(node, list):
        return [strip(v) for v in node]
    return node


def refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str):
                out.add(v)
            else:
                refs(v, out)
    elif isinstance(node, list):
        for v in node:
            refs(v, out)


def resolve(spec, ref):
    node = spec
    for part in ref.lstrip("#/").split("/"):
        node = node[part.replace("~1", "/").replace("~0", "~")]
    return node


def extract(spec):
    paths = {}
    for path in PATHS:
        item = spec.get("paths", {}).get(path)
        if item is None or "get" not in item:
            paths[path] = None
            continue
        operation = dict(item["get"])
        if "parameters" in item:
            operation["parameters"] = item["parameters"] + operation.get("parameters", [])
        paths[path] = strip(operation)
    components, pending, seen = {}, set(), set()
    refs(paths, pending)
    while pending:
        ref = pending.pop()
        if ref in seen or not ref.startswith("#/"):
            continue
        seen.add(ref)
        target = strip(resolve(spec, ref))
        components[ref] = target
        refs(target, pending)
    info = spec.get("info", {})
    return {"info": {"title": info.get("title"), "version": info.get("version")},
            "openapi": spec.get("openapi"), "paths": paths, "components": components}


def main(argv):
    if len(argv) != 2:
        sys.exit("Aufruf: extract.py <openapi.json>")
    with open(argv[1], encoding="utf-8") as f:
        spec = json.load(f)
    json.dump(extract(spec), sys.stdout, indent=1, sort_keys=True, ensure_ascii=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
