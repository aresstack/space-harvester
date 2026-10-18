#!/usr/bin/env bash
# Kopiert die KeePassRPC-Anbindung (security-keepassrpc) unverändert aus aresstack/enterprise-ai-client
# in dieses Subprojekt, oder prüft, dass die Kopie noch dem festgehaltenen Upstream-Stand entspricht.
#
#   sync-from-upstream.sh sync <commit|tag|branch>   neu kopieren und UPSTREAM.sha256 schreiben
#   sync-from-upstream.sh verify                     Kopie gegen den Upstream-Commit aus UPSTREAM.sha256 prüfen
#
# Ohne lokalen Checkout wird der Upstream nach einem temporären Verzeichnis geklont.
# ENTERPRISE_AI_CLIENT_DIR=<pfad> nutzt stattdessen einen vorhandenen Checkout (er wird nicht verändert,
# der Commit wird per git archive gelesen).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo_url="https://github.com/aresstack/enterprise-ai-client.git"
manifest="$here/UPSTREAM.sha256"

# Ganze Module: <Modulpfad im Upstream>. adyton und SecretRef kommen aus :enterprise-ai-sources.
modules=(
  proasteion/platform/security-keepassrpc
)

usage() {
  sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

# Upstream-Stand <ref> als Dateibaum nach $1 auspacken; gibt den aufgelösten Commit aus.
export_upstream() {
  local target="$1" ref="$2" source_repo
  if [ -n "${ENTERPRISE_AI_CLIENT_DIR:-}" ]; then
    source_repo="$ENTERPRISE_AI_CLIENT_DIR"
  else
    source_repo="$target.git"
    git clone --quiet --bare --filter=blob:none "$repo_url" "$source_repo"
  fi
  local commit
  commit="$(git -C "$source_repo" rev-parse --verify "$ref^{commit}")"
  mkdir -p "$target"
  git -C "$source_repo" archive "$commit" | tar -x -C "$target"
  echo "$commit"
}

# Auswahl aus dem Upstream-Baum $1 nach $2/src und $2/LICENSE kopieren.
copy_selection() {
  local upstream="$1" dest="$2"
  for module in "${modules[@]}"; do
    for set in main test testFixtures; do
      if [ -d "$upstream/$module/src/$set" ]; then
        mkdir -p "$dest/src/$set"
        cp -R "$upstream/$module/src/$set/." "$dest/src/$set/"
      fi
    done
  done
  cp "$upstream/LICENSE" "$dest/LICENSE"
}

# Prüfsummen aller kopierten Dateien, sortiert und mit relativen Pfaden (für den Gradle-Check).
checksums() {
  (cd "$1" && { echo LICENSE; find src -type f; } | LC_ALL=C sort | xargs sha256sum)
}

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

case "${1:-}" in
  sync)
    [ $# -eq 2 ] || usage
    commit="$(export_upstream "$workdir/upstream" "$2")"
    rm -rf "$here/src" "$here/LICENSE"
    copy_selection "$workdir/upstream" "$here"
    { echo "# upstream $repo_url $commit"; checksums "$here"; } > "$manifest"
    echo "Kopiert aus $commit; UPSTREAM.sha256 aktualisiert."
    ;;
  verify)
    [ $# -eq 1 ] || usage
    commit="$(sed -n 's/^# upstream [^ ]* \([0-9a-f]\{40\}\)$/\1/p' "$manifest")"
    [ -n "$commit" ] || { echo "Kein Upstream-Commit in $manifest" >&2; exit 1; }
    export_upstream "$workdir/upstream" "$commit" > /dev/null
    copy_selection "$workdir/upstream" "$workdir/expected"
    if diff -r "$workdir/expected/src" "$here/src" && diff "$workdir/expected/LICENSE" "$here/LICENSE"; then
      echo "Kopie entspricht Upstream-Commit $commit."
    else
      echo "Kopie weicht von Upstream-Commit $commit ab (siehe Diff oben)." >&2
      exit 1
    fi
    ;;
  *)
    usage
    ;;
esac
