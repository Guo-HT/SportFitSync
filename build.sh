#!/usr/bin/env bash
#
# Reproducible Gradle entry point for this project.
#
# The build deliberately keeps every writable artefact inside the repository
# (`.tools/`, `.gradle-home/`, `.android-home/`) instead of `~/.gradle` and
# `~/.android`, so it works in sandboxed environments and never pollutes the
# developer's global state. All three directories are disposable.
#
# Usage:
#   ./build.sh :app:assembleDebug
#   ./build.sh :app:installDebug
#   ./build.sh clean
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GRADLE_DIST="$ROOT/.tools/gradle-8.11.1"
ANDROID_STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

if [[ ! -x "$GRADLE_DIST/bin/gradle" ]]; then
  echo "Gradle distribution missing. Fetching 8.11.1 into .tools/ ..." >&2
  mkdir -p "$ROOT/.tools"
  curl -# -L -o "$ROOT/.tools/gradle.zip" \
    https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip
  (cd "$ROOT/.tools" && unzip -q -o gradle.zip && rm -f gradle.zip)
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "$ANDROID_STUDIO_JBR/bin/java" ]]; then
    export JAVA_HOME="$ANDROID_STUDIO_JBR"
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
  fi
fi

export GRADLE_USER_HOME="$ROOT/.gradle-home"
export ANDROID_USER_HOME="$ROOT/.android-home"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"

exec "$GRADLE_DIST/bin/gradle" --project-dir "$ROOT" "$@"
