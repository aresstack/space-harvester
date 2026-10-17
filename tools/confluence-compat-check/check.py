#!/usr/bin/env python3
"""Live-Vergleich der Confluence-Fassade (#31) mit einer echten Confluence-Data-Center-Instanz.

Stellt dieselben lesenden Anfragen an Confluence und an die Fassade des Harvesters und listet, worin sich Status,
Felder und Hüllen unterscheiden. Dokumentierte Abweichungen (docs/CONFLUENCE_COMPAT.md) stehen getrennt von
unerwarteten. Der Bericht nennt Feldpfade, Statuscodes und Anzahlen, aber keine Feldwerte, Titel oder Texte.

Zugangsdaten nur über Umgebungsvariablen, siehe docs/CONFLUENCE_COMPAT.md, Abschnitt „Live-Vergleich“.
Nur Python 3, keine weiteren Pakete.
"""

import argparse
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

# Abrufform beim Ernten; die Fassade liefert immer genau diese Erweiterungen.
STORED_EXPAND = "ancestors,body.storage,history,history.lastUpdated,metadata.labels,space,version"
SMALL_EXPAND = "body.storage,version"
UNSTORED_EXPAND = "body.view"

EXPECTED = "erwartet"
UNEXPECTED = "unerwartet"


class Response:
    def __init__(self, status, body, error=None):
        self.status = status
        self.body = body
        self.error = error

    def json(self):
        try:
            return json.loads(self.body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, AttributeError):
            return None


class Endpoint:
    """Eine Seite des Vergleichs: Basis-URL plus Anmeldung, TLS und Proxy."""

    def __init__(self, name, base, headers, context, proxy):
        self.name = name
        self.base = base.rstrip("/")
        self.headers = headers
        handlers = [urllib.request.HTTPSHandler(context=context)]
        # Ohne ausdrücklichen Proxy keinen aus der Umgebung übernehmen: der Harvester läuft meist lokal.
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy} if proxy else {}))
        self.opener = urllib.request.build_opener(*handlers)

    def get(self, path, params=None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        request = urllib.request.Request(url, headers=dict(self.headers, Accept="application/json"))
        try:
            with self.opener.open(request, timeout=60) as response:
                return Response(response.status, response.read())
        except urllib.error.HTTPError as e:
            return Response(e.code, e.read())
        except (urllib.error.URLError, OSError) as e:
            # Nur die Art des Fehlers, keine URL mit Anmeldedaten.
            return Response(None, b"", type(e).__name__ + ": " + str(getattr(e, "reason", e)))


def tls_context(ca_file, cert_file, key_file, key_password):
    context = ssl.create_default_context(cafile=ca_file) if ca_file else ssl.create_default_context()
    if cert_file:
        context.load_cert_chain(cert_file, key_file, key_password)
    return context


def confluence_endpoint(env):
    headers = {}
    if env.get("CONFLUENCE_PAT"):
        headers["Authorization"] = "Bearer " + env["CONFLUENCE_PAT"]
    elif env.get("CONFLUENCE_USER"):
        import base64
        pair = (env["CONFLUENCE_USER"] + ":" + env.get("CONFLUENCE_PASSWORD", "")).encode("utf-8")
        headers["Authorization"] = "Basic " + base64.b64encode(pair).decode("ascii")
    context = tls_context(env.get("CONFLUENCE_CA_FILE"), env.get("CONFLUENCE_CLIENT_CERT"),
                          env.get("CONFLUENCE_CLIENT_KEY"), env.get("CONFLUENCE_CLIENT_KEY_PASSWORD"))
    return Endpoint("Confluence", env["CONFLUENCE_BASE"], headers, context, env.get("CONFLUENCE_PROXY"))


def harvester_endpoint(env):
    headers = {"Authorization": "Bearer " + env["HARVESTER_TOKEN"]} if env.get("HARVESTER_TOKEN") else {}
    context = tls_context(env.get("HARVESTER_CA_FILE"), None, None, None)
    return Endpoint("Fassade", env["HARVESTER_BASE"], headers, context, env.get("HARVESTER_PROXY"))


# ---------------------------------------------------------------------------------------------------------------
# Vergleich


def diff(left, right, path=""):
    """Unterschiede zwischen zwei JSON-Werten als (Pfad, Art); Werte selbst werden nie zurückgegeben."""
    if isinstance(left, dict) and isinstance(right, dict):
        out = []
        for key in sorted(set(left) | set(right)):
            sub = path + "." + key if path else key
            if key not in right:
                out.append((sub, "fehlt in der Fassade"))
            elif key not in left:
                out.append((sub, "nur in der Fassade"))
            else:
                out.extend(diff(left[key], right[key], sub))
        return out
    if isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right):
            return [(path + "[]", "Länge %d gegenüber %d" % (len(left), len(right)))]
        out = []
        for i, (a, b) in enumerate(zip(left, right)):
            out.extend(diff(a, b, "%s[%d]" % (path, i)))
        return out
    if type(left) is not type(right):
        return [(path, "Typ %s gegenüber %s" % (type(left).__name__, type(right).__name__))]
    return [] if left == right else [(path, "Wert verschieden")]


def generic(path):
    """Pfad ohne Listenindizes, für die Einordnung."""
    out, depth = [], 0
    for ch in path:
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
        elif depth == 0:
            out.append(ch)
    return "".join(out)


def classify(path, kind, requested_expand):
    """Ordnet einen Unterschied einer dokumentierten Abweichung zu oder nicht."""
    g = generic(path)
    top = g.split(".")[0]
    stored = set(STORED_EXPAND.split(","))
    if g.startswith("_links") or g.endswith("._links") or "._links." in g:
        return EXPECTED, "_links zeigen auf die Instanz zum Erntezeitpunkt"
    if g.startswith("_expandable") or "._expandable" in g:
        return EXPECTED, "_expandable hängt vom angefragten expand ab"
    if kind == "nur in der Fassade" and requested_expand is not None:
        asked = set(requested_expand.split(",")) if requested_expand else set()
        covered = any(s == g or s.startswith(g + ".") or g.startswith(s + ".") for s in stored)
        not_asked = not any(a == g or a.startswith(g + ".") or g.startswith(a + ".") for a in asked)
        if covered and not_asked:
            return EXPECTED, "die Fassade liefert alle gespeicherten Erweiterungen"
    if top in ("history", "version") and any(p in g for p in ("contributors", "previousVersion", "nextVersion",
                                                                "lastUpdated.by", "createdBy")):
        return UNEXPECTED, "benutzerbezogenes Feld (Dienstkonto zum Erntezeitpunkt?)"
    return UNEXPECTED, ""


class Report:
    def __init__(self):
        self.sections = []
        self.unexpected = 0

    def section(self, title):
        self.sections.append([title, []])

    def line(self, text, unexpected=False):
        if unexpected:
            self.unexpected += 1
        self.sections[-1][1].append(("UNERWARTET  " if unexpected else "") + text)

    def render(self):
        out = ["# Live-Vergleich Confluence-Fassade (#31)", ""]
        for title, lines in self.sections:
            out.append("## " + title)
            out.append("")
            out.extend("- " + line for line in lines or ["keine Unterschiede"])
            out.append("")
        out.append("Unerwartete Unterschiede: %d" % self.unexpected)
        return "\n".join(out) + "\n"


def status_text(response):
    return str(response.status) if response.status is not None else "kein Abruf (" + response.error + ")"


def compare_page_fields(report, label, conf, fac, expand):
    differences = diff(conf, fac)
    counts = {}
    for path, kind in differences:
        verdict, reason = classify(path, kind, expand)
        key = (generic(path), kind, verdict, reason)
        counts[key] = counts.get(key, 0) + 1
    for (path, kind, verdict, reason), n in sorted(counts.items()):
        text = "%s: `%s` %s%s%s" % (label, path, kind, " (%dx)" % n if n > 1 else "",
                                    " – " + reason if reason else "")
        report.line(text, verdict == UNEXPECTED)


def version_of(document):
    try:
        return int(document["version"]["number"])
    except (KeyError, TypeError, ValueError):
        return None


def check_page(report, confluence, facade, page_id):
    report.section("Einzelseite %s" % page_id)
    stored_conf = None
    for expand in (None, SMALL_EXPAND, STORED_EXPAND):
        params = {"expand": expand} if expand else None
        label = "expand=" + (expand if expand else "(ohne)")
        c = confluence.get("/rest/api/content/" + page_id, params)
        f = facade.get("/rest/api/content/" + page_id, params)
        if c.status != f.status:
            report.line("%s: Status %s gegenüber %s" % (label, status_text(c), status_text(f)), True)
            continue
        report.line("%s: Status %s auf beiden Seiten" % (label, status_text(c)))
        if c.status != 200:
            continue
        cj, fj = c.json(), f.json()
        if cj is None or fj is None:
            report.line("%s: Antwort ist kein JSON" % label, True)
            continue
        if expand == STORED_EXPAND:
            stored_conf = cj
            vc, vf = version_of(cj), version_of(fj)
            if vc is not None and vf is not None and vc != vf:
                report.line("version.number: Confluence %d, Fassade %d – Cache veraltet, Space neu abgleichen und "
                            "erneut vergleichen" % (vc, vf), True)
            if c.body == f.body:
                report.line("%s: Bytes identisch" % label)
        compare_page_fields(report, label, cj, fj, expand or "")

    c = confluence.get("/rest/api/content/" + page_id, {"expand": UNSTORED_EXPAND})
    f = facade.get("/rest/api/content/" + page_id, {"expand": UNSTORED_EXPAND})
    if f.status == 400:
        report.line("expand=%s: Confluence %s, Fassade 400 – erwartet, nicht gespeicherte Erweiterung"
                    % (UNSTORED_EXPAND, status_text(c)))
    else:
        report.line("expand=%s: Fassade %s statt 400" % (UNSTORED_EXPAND, status_text(f)), True)

    f = facade.get("/rest/api/content/" + page_id, {"status": "historical"})
    report.line("status=historical: Fassade %s%s" % (status_text(f), "" if f.status == 400 else " statt 400"),
                f.status != 400)
    return stored_conf


def compare_error_body(report, label, c, f):
    cj, fj = c.json(), f.json()
    if not isinstance(fj, dict):
        report.line("%s: Fehlerkörper der Fassade ist kein JSON-Objekt" % label, True)
        return
    if not isinstance(cj, dict):
        report.line("%s: Fehlerkörper von Confluence ist kein JSON-Objekt, Form nicht vergleichbar" % label)
        return
    for path, kind in diff(cj, fj):
        g = generic(path)
        if g in ("message",) and kind == "Wert verschieden":
            continue
        report.line("%s: Fehlerkörper `%s` %s" % (label, g, kind), True)


def check_errors(report, confluence, facade, missing_id, restricted_id):
    report.section("Fehlerfälle")
    c = confluence.get("/rest/api/content/" + missing_id)
    f = facade.get("/rest/api/content/" + missing_id)
    report.line("fehlende Seite %s: Confluence %s, Fassade %s" % (missing_id, status_text(c), status_text(f)),
                c.status != f.status)
    if f.status == c.status and f.status is not None and f.status >= 400:
        compare_error_body(report, "fehlende Seite", c, f)
    if restricted_id:
        c = confluence.get("/rest/api/content/" + restricted_id)
        f = facade.get("/rest/api/content/" + restricted_id)
        leak = f.status == 200 and c.status != 200
        report.line("eingeschränkte Seite %s: Confluence %s, Fassade %s%s" % (
            restricted_id, status_text(c), status_text(f),
            " – die Fassade zeigt eine Seite, die Confluence diesem Konto verweigert; Freigaben des Harvesters "
            "für den Client prüfen" if leak else ""), leak or (c.status != f.status and f.status != 404))


def collect(endpoint, path, params, max_pages, page_size):
    """Blättert über start/limit und folgt _links.next, bis die Liste endet oder max_pages erreicht ist."""
    results, envelopes, start = [], [], 0
    while len(results) < max_pages:
        r = endpoint.get(path, dict(params, start=start, limit=page_size))
        if r.status != 200:
            return results, envelopes, r
        body = r.json()
        if not isinstance(body, dict) or not isinstance(body.get("results"), list):
            return results, envelopes, r
        envelopes.append(body)
        results.extend(body["results"])
        if not body["results"] or "next" not in body.get("_links", {}):
            return results, envelopes, None
        start += len(body["results"])
    return results, envelopes, None


def check_envelope(report, label, envelopes, page_size):
    for i, body in enumerate(envelopes):
        missing = [k for k in ("results", "start", "limit", "size", "_links") if k not in body]
        if missing:
            report.line("%s, Stapel %d: Hülle ohne %s" % (label, i, ", ".join(missing)), True)
        if body.get("size") != len(body.get("results", [])):
            report.line("%s, Stapel %d: size passt nicht zu results" % (label, i), True)
        if body.get("limit") != page_size:
            report.line("%s, Stapel %d: limit %s statt %d" % (label, i, body.get("limit"), page_size), True)


def check_list(report, confluence, facade, label, path, params, max_pages, page_size):
    report.section(label)
    full = dict(params, expand=STORED_EXPAND)
    cr, ce, cerr = collect(confluence, path, full, max_pages, page_size)
    fr, fe, ferr = collect(facade, path, full, max_pages, page_size)
    if cerr or ferr:
        report.line("Abruf: Confluence %s, Fassade %s" % (status_text(cerr) if cerr else "200",
                                                          status_text(ferr) if ferr else "200"),
                    (cerr.status if cerr else 200) != (ferr.status if ferr else 200))
        if cerr and ferr:
            return
    check_envelope(report, "Confluence", ce, page_size)
    check_envelope(report, "Fassade", fe, page_size)
    cids = [str(item.get("id")) for item in cr if isinstance(item, dict)]
    fids = [str(item.get("id")) for item in fr if isinstance(item, dict)]
    capped = len(cids) >= max_pages or len(fids) >= max_pages
    report.line("Seiten: Confluence %d, Fassade %d (Stapelgröße %d%s)" % (
        len(cids), len(fids), page_size, ", bei --max-pages abgebrochen" if capped else ""))
    if len(set(fids)) != len(fids):
        report.line("Fassade liefert Seiten doppelt über die Stapel", True)
    if not capped:
        only_c, only_f = set(cids) - set(fids), set(fids) - set(cids)
        if only_c:
            report.line("%d Seite(n) nur bei Confluence – nicht geerntet, ohne Freigabe für den Client oder seit dem "
                        "Abgleich neu: %s" % (len(only_c), ", ".join(sorted(only_c)[:20])), True)
        if only_f:
            report.line("%d Seite(n) nur in der Fassade – seit dem Abgleich gelöscht oder verschoben, oder für das "
                        "Konto bei Confluence nicht sichtbar: %s" % (len(only_f), ", ".join(sorted(only_f)[:20])),
                        True)
    if cids != sorted(cids) and sorted(cids) == sorted(fids):
        report.line("Reihenfolge verschieden – erwartet, die Fassade sortiert nach Content-ID als Zeichenkette")
    by_id = {str(item.get("id")): item for item in cr if isinstance(item, dict)}
    for item in fr:
        if isinstance(item, dict) and str(item.get("id")) in by_id:
            compare_page_fields(report, "Element %s" % item.get("id"), by_id[str(item.get("id"))], item,
                                STORED_EXPAND)


def run(args, env, out):
    confluence, facade = confluence_endpoint(env), harvester_endpoint(env)
    report = Report()
    report.section("Aufbau")
    report.line("Stapelgröße %d, höchstens %d Seiten je Liste; Seiten: %s" % (
        args.page_size, args.max_pages, ", ".join(args.page) or "keine"))
    for page_id in args.page:
        check_page(report, confluence, facade, page_id)
    check_errors(report, confluence, facade, args.missing_page, args.restricted_page)
    if args.space:
        check_list(report, confluence, facade, "Liste content?spaceKey=%s" % args.space, "/rest/api/content",
                   {"spaceKey": args.space, "type": "page"}, args.max_pages, args.page_size)
        if args.title:
            check_list(report, confluence, facade, "Liste content?spaceKey=%s&title=…" % args.space,
                       "/rest/api/content", {"spaceKey": args.space, "type": "page", "title": args.title},
                       args.max_pages, args.page_size)
    for parent in args.parent:
        check_list(report, confluence, facade, "Kinder von %s" % parent, "/rest/api/content/%s/child/page" % parent,
                   {}, args.max_pages, args.page_size)
    out.write(report.render())
    return 1 if report.unexpected else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--page", action="append", default=[], help="Content-ID einer geernteten Seite (mehrfach)")
    parser.add_argument("--space", help="Space-Key für content?spaceKey=…")
    parser.add_argument("--title", help="exakter Seitentitel für den Titelfilter (erscheint nicht im Bericht)")
    parser.add_argument("--parent", action="append", default=[], help="Content-ID für child/page (mehrfach)")
    parser.add_argument("--missing-page", default="999999999999", help="Content-ID, die es nicht gibt")
    parser.add_argument("--restricted-page", help="Content-ID einer Seite mit Leseeinschränkung")
    parser.add_argument("--page-size", type=int, default=25, help="limit je Stapel (Standard 25, höchstens 100)")
    parser.add_argument("--max-pages", type=int, default=500, help="höchstens so viele Seiten je Liste lesen")
    args = parser.parse_args(argv)
    for name in ("CONFLUENCE_BASE", "HARVESTER_BASE"):
        if not os.environ.get(name):
            parser.error("Umgebungsvariable %s fehlt" % name)
    return run(args, os.environ, sys.stdout)


if __name__ == "__main__":
    sys.exit(main())
