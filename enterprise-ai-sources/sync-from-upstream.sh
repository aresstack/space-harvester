#!/usr/bin/env bash
# Kopiert die Confluence-Quelle, die Dateiquelle, die Git- und die FTP/JES-Quelle (#187), den Chat-Adapter (ADR 0017), die Dokument-Extraktion (#183) und ihre
# Basismodule unverändert aus aresstack/enterprise-ai-client in dieses Subprojekt, oder prüft, dass die Kopie noch dem festgehaltenen Upstream-Stand entspricht.
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

# Ganze Module: <Modulpfad im Upstream>
modules=(
  adyton
  proasteion/platform/network
  source-api
  proasteion/emporion/holkas/source-confluence
  proasteion/emporion/deigma
  proasteion/emporion/deigma/document-tika
  proasteion/emporion/holkas/source-localfiles
  proasteion/emporion/holkas/source-mediawiki
  proasteion/emporion/holkas/source-git
  proasteion/emporion/holkas/source-ftp
  proasteion/emporion/holkas/source-ndv
  proasteion/emporion/holkas/source-sharepoint
  proasteion/emporion/holkas/source-betaview
  proasteion/emporion/holkas/source-outlook
  embedding-api
  embedding-openai
  chat-api
  chat-openai
)

# Einzelne Klassen aus weiteren Modulen: <Modulpfad>:<Klassenpfad unter src/main/java ohne .java>.
# Text aus Dateien (#183): SourceFileTextExtractor (Port aus astu/acropolis) und DocumentSourceFileTextExtractor (Dokumente
# über document-tika, Bilder über die Bildbeschreibung); dessen Bildweg braucht ImageAnalysis, ImageAnalysisCache und
# SourceImageAnalysisUseCase, die der Space Harvester nicht aufruft.
# KipitzRerankAdapter (#181) liest Fehlermeldungen über KipitzSpeechAdapter.errorMessage; deshalb kommen der
# Sprachadapter und seine drei Sprach-Schnittstellen mit, ohne dass der Space Harvester sie benutzt.
module_classes=(
  astu/acropolis:com/aresstack/enterpriseai/astu/acropolis/knowledge/SourceFileTextExtractor
  astu/acropolis:com/aresstack/enterpriseai/astu/acropolis/image/ImageAnalysis
  astu/acropolis:com/aresstack/enterpriseai/astu/acropolis/image/ImageAnalysisCache
  astu/acropolis:com/aresstack/enterpriseai/astu/acropolis/image/SourceImageAnalysisUseCase
  proasteion/application:com/aresstack/enterpriseai/proasteion/application/knowledge/DocumentSourceFileTextExtractor
  # MediaWiki (#187): Brücke vom SecretProvider zur Anmeldung am Wiki, wie AdapterAssembly.sourceProviders im Upstream
  proasteion/application:com/aresstack/enterpriseai/proasteion/application/security/SecretBackedMediaWikiCredentialsProvider
  model-api:com/aresstack/enterpriseai/model/api/ModelCatalogPort
  model-api:com/aresstack/enterpriseai/model/api/ModelCatalogException
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzModelCatalogAdapter
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzModelCatalogConfig
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzModelListParser
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzConnections
  model-api:com/aresstack/enterpriseai/model/api/RerankPort
  model-api:com/aresstack/enterpriseai/model/api/RerankException
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzRerankAdapter
  model-kipitz:com/aresstack/enterpriseai/model/kipitz/KipitzSpeechAdapter
  speech-api:com/aresstack/enterpriseai/speech/api/SpeechSynthesisPort
  speech-api:com/aresstack/enterpriseai/speech/api/SpeechSynthesisException
  speech-api:com/aresstack/enterpriseai/speech/api/SynthesizedSpeech
)

# Aus astu nur die Klassen, die die Module oben (inklusive Tests) transitiv brauchen.
astu_classes=(
  knowledge/KnowledgeDocument knowledge/KnowledgeMetadata knowledge/KnowledgeResource
  knowledge/KnowledgeResourceId knowledge/KnowledgeRevision knowledge/KnowledgeStructuralBlock
  knowledge/KnowledgeText knowledge/SourceAddress knowledge/UnicodeClasses
  knowledge/SentenceSplitter knowledge/KnowledgeTokenCounter knowledge/WordAndSymbolTokenCounter
  knowledge/KnowledgeChunkingPolicy
  security/SecretRef
  embedding/EmbeddingModelIdentity embedding/EmbeddingVector embedding/EmbeddingWorldMismatchException
  modelcatalog/ModelCategory modelcatalog/ModelClassification modelcatalog/ModelDescriptor
  modelcatalog/ModelReference
  source/KnowledgeSourceType source/SourceDefinition source/SourceLocators
  source/SourceSettingField source/SourceSettings
  chat/ChatConversation chat/ChatConversationId chat/ChatFinishReason chat/ChatImage chat/ChatMessage
  chat/ChatOptions chat/ChatRequest chat/ChatResponse chat/ChatRole chat/ChatToolCall chat/ChatToolDefinition
  chat/ChatUsage
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
  local entry module class
  for entry in "${module_classes[@]}"; do
    module="${entry%%:*}"
    class="${entry#*:}"
    mkdir -p "$dest/src/main/java/$(dirname "$class")"
    cp "$upstream/$module/src/main/java/$class.java" "$dest/src/main/java/$class.java"
    if [ -f "$upstream/$module/src/test/java/${class}Test.java" ]; then
      mkdir -p "$dest/src/test/java/$(dirname "$class")"
      cp "$upstream/$module/src/test/java/${class}Test.java" "$dest/src/test/java/${class}Test.java"
    fi
  done
  local astu="com/aresstack/enterpriseai/astu"
  for class in "${astu_classes[@]}"; do
    mkdir -p "$dest/src/main/java/$astu/$(dirname "$class")"
    cp "$upstream/astu/src/main/java/$astu/$class.java" "$dest/src/main/java/$astu/$class.java"
    if [ -f "$upstream/astu/src/test/java/$astu/${class}Test.java" ]; then
      mkdir -p "$dest/src/test/java/$astu/$(dirname "$class")"
      cp "$upstream/astu/src/test/java/$astu/${class}Test.java" "$dest/src/test/java/$astu/${class}Test.java"
    fi
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
