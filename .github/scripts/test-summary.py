"""Fasst JUnit-XML-Ergebnisse als Markdown für die GitHub-Job-Übersicht zusammen (#15).

Mit --log als erstem Argument schreibt es stattdessen jeden fehlgeschlagenen Test mit vollem Stacktrace ins
Job-Log, damit der Fehler ohne Herunterladen des Testberichts lesbar ist.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

log_mode = len(sys.argv) > 1 and sys.argv[1] == "--log"
roots = sys.argv[2:] if log_mode else sys.argv[1:]

suites = []
for root in roots:
    for path in sorted(pathlib.Path(root).rglob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        suites.append(suite)

if log_mode:
    for s in suites:
        for case in s.iter("testcase"):
            for problem in list(case.findall("failure")) + list(case.findall("error")):
                print(f"::error title=Test fehlgeschlagen::{s.get('name')}.{case.get('name')}")
                print(f"===== FEHLGESCHLAGEN: {s.get('name')} > {case.get('name')}")
                print(problem.text or problem.get("message", ""))
                for stream in ("system-out", "system-err"):
                    out = case.findtext(stream)
                    if out:
                        print(f"----- {stream}")
                        print(out)
    sys.exit(0)

total = sum(int(s.get("tests", 0)) for s in suites)
failed = sum(int(s.get("failures", 0)) + int(s.get("errors", 0)) for s in suites)
skipped = sum(int(s.get("skipped", 0)) for s in suites)
seconds = sum(float(s.get("time", 0)) for s in suites)

print("## Tests")
print()
if not suites:
    print("Keine Testergebnisse gefunden (Build vor den Tests abgebrochen?).")
    sys.exit(0)
print(f"{total} Tests in {len(suites)} Klassen, {failed} fehlgeschlagen, {skipped} übersprungen, {seconds:.0f} s")
failures = [
    (s.get("name"), case.get("name"), problem.get("message", "").splitlines()[0][:200] if problem.get("message") else "")
    for s in suites
    for case in s.iter("testcase")
    for problem in list(case.findall("failure")) + list(case.findall("error"))
]
if failures:
    print()
    print("| Klasse | Test | Meldung |")
    print("|---|---|---|")
    for suite, case, message in failures:
        print(f"| `{suite}` | {case} | {message.replace('|', '/')} |")
slowest = sorted(suites, key=lambda s: float(s.get("time", 0)), reverse=True)[:5]
print()
print("Langsamste Klassen: " + ", ".join(f"`{s.get('name').rsplit('.', 1)[-1]}` {float(s.get('time', 0)):.1f} s" for s in slowest))
