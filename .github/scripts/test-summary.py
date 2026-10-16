"""Fasst JUnit-XML-Ergebnisse als Markdown für die GitHub-Job-Übersicht zusammen (#15)."""
import pathlib
import sys
import xml.etree.ElementTree as ET

suites = []
for root in sys.argv[1:]:
    for path in sorted(pathlib.Path(root).rglob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        suites.append(suite)

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
