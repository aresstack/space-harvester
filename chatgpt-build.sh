#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

export GRADLE_USER_HOME="$ROOT_DIR/.chatgpt/gradle-home"

if [[ ! -d "$GRADLE_USER_HOME/wrapper/dists" ]] ||
    ! find "$GRADLE_USER_HOME/wrapper/dists" -name '*.zip.ok' -print -quit | grep -q .; then
    echo "Missing prepared Gradle distribution in $GRADLE_USER_HOME" >&2
    echo "Download the ChatGPT-compatible release ZIP instead of a source checkout." >&2
    exit 1
fi

if [[ ! -d "$GRADLE_USER_HOME/caches/modules-2/files-2.1" ]]; then
    echo "Missing prepared dependency cache in $GRADLE_USER_HOME" >&2
    exit 1
fi

GRADLE_ARGS=(--offline --no-daemon --console=plain clean build)

if command -v xvfb-run >/dev/null 2>&1; then
    exec xvfb-run -a bash "$ROOT_DIR/gradlew" "${GRADLE_ARGS[@]}" "$@"
fi

exec bash "$ROOT_DIR/gradlew" "${GRADLE_ARGS[@]}" "$@"
