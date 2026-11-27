#!/usr/bin/env bash
# Kopiert eine Bibliothek aus ihrem eigenen Repository (aresstack/<bibliothek>) unverändert nach libs/<bibliothek>, oder
# prüft, dass die Kopie noch dem festgehaltenen Upstream-Stand entspricht. Gradle bindet jede Kopie als eigenes
# Gradle-Projekt per Composite Build ein (settings.gradle); so baut der Space Harvester ohne Maven-Central-Release.
#
#   sync-from-upstream.sh sync <bibliothek> <commit|tag|branch>   neu kopieren und upstream/<bibliothek>.sha256 schreiben
#   sync-from-upstream.sh verify [<bibliothek>]                   Kopie(n) gegen den festgehaltenen Upstream-Commit prüfen
#
# Bibliotheken: knowledge4j, research4j, writing4j (Liste unten). Ohne lokalen Checkout wird der Upstream in ein
# temporäres Verzeichnis geklont. <BIBLIOTHEK>_DIR=<pfad> (z. B. RESEARCH4J_DIR) nutzt stattdessen einen vorhandenen
# Checkout (er wird nicht verändert, der Commit wird per git archive gelesen).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
libraries=(knowledge4j research4j writing4j)
# Nicht mitkopiert: die CI des Upstreams und sein Gradle-Wrapper (ein eingebundener Build läuft mit dem Gradle des
# Space Harvester).
excludes=(.github gradlew gradlew.bat gradle/wrapper)

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

known() {
  local lib
  for lib in "${libraries[@]}"; do [ "$lib" = "$1" ] && return 0; done
  echo "Unbekannte Bibliothek: $1 (bekannt: ${libraries[*]})" >&2
  exit 2
}

# Upstream-Stand <ref> der Bibliothek $1 als Dateibaum nach $2 auspacken; gibt den aufgelösten Commit aus.
export_upstream() {
  local lib="$1" target="$2" ref="$3" source_repo dir_var
  dir_var="$(echo "$lib" | tr '[:lower:]' '[:upper:]')_DIR"
  if [ -n "${!dir_var:-}" ]; then
    source_repo="${!dir_var}"
  else
    source_repo="$target.git"
    git clone --quiet --bare --filter=blob:none "https://github.com/aresstack/$lib.git" "$source_repo"
  fi
  local commit
  commit="$(git -C "$source_repo" rev-parse --verify "$ref^{commit}")"
  mkdir -p "$target"
  git -C "$source_repo" archive "$commit" | tar -x -C "$target"
  local path
  for path in "${excludes[@]}"; do rm -rf "${target:?}/$path"; done
  echo "$commit"
}

# Prüfsummen aller kopierten Dateien, sortiert und mit relativen Pfaden, für die Gradle-Prüfung verify<Bibliothek>Copy
# (gradle/upstream-copy.gradle). Wie dort zählt CRLF wie LF (ein Git-Checkout darf Zeilenenden umstellen); .gitignore und
# .gitattributes fehlen, weil Gradle sie in Dateibäumen grundsätzlich auslässt (sie werden trotzdem mitkopiert).
checksums() {
  local file
  (cd "$1" && find . -type f ! -name .gitignore ! -name .gitattributes | sed 's|^\./||' | LC_ALL=C sort |
    while IFS= read -r file; do
      printf '%s  %s\n' "$(perl -0777 -pe 's/\r\n/\n/g' "$file" | sha256sum | cut -d' ' -f1)" "$file"
    done)
}

workdir="$(mktemp -d)"
# Ein Hintergrundlauf von git (commit-graph nach dem Nachladen des Teilklons) kann beim Aufräumen noch schreiben; das
# Aufräumen darf das Ergebnis der Prüfung nicht ändern.
trap 'rm -rf "$workdir" 2>/dev/null || { sleep 1; rm -rf "$workdir" 2>/dev/null; } || true' EXIT

verify_one() {
  local lib="$1" manifest="$here/upstream/$1.sha256" commit
  commit="$(sed -n 's/^# upstream [^ ]* \([0-9a-f]\{40\}\)$/\1/p' "$manifest")"
  [ -n "$commit" ] || { echo "Kein Upstream-Commit in $manifest" >&2; return 1; }
  export_upstream "$lib" "$workdir/$lib" "$commit" > /dev/null
  # Build-Ausgaben eines lokalen Laufs liegen nicht im Upstream und zählen nicht.
  if diff -r --strip-trailing-cr -x build -x .gradle "$workdir/$lib" "$here/$lib"; then
    echo "$lib entspricht Upstream-Commit $commit."
  else
    echo "$lib weicht von Upstream-Commit $commit ab (siehe Diff oben)." >&2
    return 1
  fi
}

case "${1:-}" in
  sync)
    [ $# -eq 3 ] || usage
    known "$2"
    lib="$2"
    commit="$(export_upstream "$lib" "$workdir/$lib" "$3")"
    rm -rf "${here:?}/$lib"
    mkdir -p "$here/upstream"
    cp -R "$workdir/$lib" "$here/$lib"
    { echo "# upstream https://github.com/aresstack/$lib.git $commit"; checksums "$here/$lib"; } > "$here/upstream/$lib.sha256"
    echo "$lib kopiert aus $commit; upstream/$lib.sha256 aktualisiert."
    ;;
  verify)
    [ $# -le 2 ] || usage
    if [ $# -eq 2 ]; then known "$2"; set -- "$2"; else set -- "${libraries[@]}"; fi
    status=0
    for lib in "$@"; do verify_one "$lib" || status=1; done
    exit $status
    ;;
  *)
    usage
    ;;
esac
