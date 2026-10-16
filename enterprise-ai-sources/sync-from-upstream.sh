#!/usr/bin/env bash
# Kopiert die Confluence-Quelle und ihre Basismodule unverändert aus einem Checkout von
# aresstack/enterprise-ai-client in dieses Subprojekt. Aufruf:
#   enterprise-ai-sources/sync-from-upstream.sh <pfad-zum-enterprise-ai-client>
# Danach UPSTREAM.md (Commit) aktualisieren und ./gradlew build ausführen.
set -euo pipefail

upstream="${1:?Pfad zum enterprise-ai-client-Checkout angeben}"
here="$(cd "$(dirname "$0")" && pwd)"

# Ganze Module: <Modulpfad im Upstream>
modules=(
  adyton
  proasteion/platform/network
  source-api
  proasteion/emporion/holkas/source-confluence
)

# Aus astu nur die Klassen, die die Module oben (inklusive Tests) transitiv brauchen.
astu_classes=(
  knowledge/KnowledgeDocument knowledge/KnowledgeMetadata knowledge/KnowledgeResource
  knowledge/KnowledgeResourceId knowledge/KnowledgeRevision knowledge/KnowledgeStructuralBlock
  knowledge/KnowledgeText knowledge/SourceAddress knowledge/UnicodeClasses
  security/SecretRef
  source/KnowledgeSourceType source/SourceDefinition source/SourceLocators
  source/SourceSettingField source/SourceSettings
)

for set in main test testFixtures; do
  rm -rf "$here/src/$set"
done

for module in "${modules[@]}"; do
  for set in main test testFixtures; do
    src="$upstream/$module/src/$set"
    if [ -d "$src" ]; then
      mkdir -p "$here/src/$set"
      cp -R "$src/." "$here/src/$set/"
    fi
  done
done

astu="com/aresstack/enterpriseai/astu"
for class in "${astu_classes[@]}"; do
  mkdir -p "$here/src/main/java/$astu/$(dirname "$class")"
  cp "$upstream/astu/src/main/java/$astu/$class.java" "$here/src/main/java/$astu/$class.java"
  test_file="$upstream/astu/src/test/java/$astu/${class}Test.java"
  if [ -f "$test_file" ]; then
    mkdir -p "$here/src/test/java/$astu/$(dirname "$class")"
    cp "$test_file" "$here/src/test/java/$astu/${class}Test.java"
  fi
done

echo "Kopiert aus $(git -C "$upstream" rev-parse HEAD)"
