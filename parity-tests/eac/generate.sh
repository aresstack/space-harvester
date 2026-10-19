#!/usr/bin/env bash
# Erzeugt die Referenzexporte des Enterprise AI Client für den Paritäts-Space (ADR 0012, #193).
#
#   parity-tests/eac/generate.sh [<pfad-zum-enterprise-ai-client>]
#
# Ohne Pfad wird der Commit aus parity-tests/src/test/resources/eac/COMMIT frisch geklont. Mit Pfad wird dessen
# aktueller Stand genommen und COMMIT danach auf ihn gesetzt. Ergebnis unter parity-tests/src/test/resources/eac/:
# parity.docx, parity.pdf, parity.md, problems-<format>.txt (nicht eingebettete Bilder), reading/ (Leseansicht),
# scope-page.md und scope-subtree.md (Einzelseite und Teilbaum ab Seite, #186), navigation.properties (Kinder,
# Breadcrumbs, Querverweise), management.properties (Quellenverwaltung), addresses.properties (Adressformen),
# search.properties (Index-Rückfall, Auszüge, Vorladen) und documents/ (Typerkennung und Dokumenttext der Dateien aus
# src/test/resources/documents).
# Braucht JDK 8 oder neuer (übersetzt mit --release 8, läuft headless). PARITY_KEEP=1 behält den Arbeitsordner zur Fehlersuche.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
target="$repo/parity-tests/src/test/resources/eac"
work="$(mktemp -d)"; echo "Arbeitsordner $work" >&2
trap '[[ -n "${PARITY_KEEP:-}" ]] || rm -rf "$work"' EXIT

if [[ $# -ge 1 ]]; then
  eac="$(cd "$1" && pwd)"
else
  commit="$(head -n 1 "$target/COMMIT")"
  eac="$work/enterprise-ai-client"
  git clone --quiet https://github.com/aresstack/enterprise-ai-client.git "$eac"
  git -C "$eac" checkout --quiet "$commit"
fi

# Klassenpfad von exedra (Writer, Use-Case, Quelle) plus Testfixtures der Confluence-Quelle (FakeConfluence).
cat > "$work/classpath.gradle" <<'EOF'
allprojects {
  afterEvaluate { p ->
    if (p.path == ':proasteion:exedra') {
      p.tasks.register('parityClasspath') {
        def fixtures = p.rootProject.project(':proasteion:emporion:holkas:source-confluence')
        dependsOn p.tasks.named('jar'), p.configurations.runtimeClasspath,
            ':proasteion:emporion:holkas:source-confluence:testFixturesJar',
            fixtures.configurations.testFixturesRuntimeClasspath
        doLast {
          def files = [p.tasks.jar.archiveFile.get().asFile] + p.configurations.runtimeClasspath.files.toList() +
              [fixtures.tasks.testFixturesJar.archiveFile.get().asFile] +
              fixtures.configurations.testFixturesRuntimeClasspath.files.toList()
          new File(System.getenv('PARITY_CP_OUT')).text = files.unique().join(File.pathSeparator)
        }
      }
    }
  }
}
EOF
(cd "$eac" && PARITY_CP_OUT="$work/cp.txt" ./gradlew --quiet --init-script "$work/classpath.gradle" \
  :proasteion:exedra:parityClasspath)
cp="$(cat "$work/cp.txt")"

mkdir -p "$work/classes"
javac --release 8 -nowarn -encoding UTF-8 -cp "$cp" -d "$work/classes" \
  "$repo/parity-tests/src/test/java/com/aresstack/spaceharvester/parity/ParitySpace.java" \
  "$here/EacParityExport.java" "$here/EacDocumentText.java"
java -Djava.awt.headless=true -cp "$work/classes${cp:+:$cp}" EacParityExport "$target"
java -Djava.awt.headless=true -cp "$work/classes${cp:+:$cp}" EacDocumentText \
  "$repo/parity-tests/src/test/resources/documents" "$target"
git -C "$eac" rev-parse HEAD > "$target/COMMIT"
echo "Referenzexporte unter $target (Enterprise AI Client $(cat "$target/COMMIT"))"
