#!/usr/bin/env bash
#
# Publish a signed release APK as a GitHub Release.
#
# Usage:
#   tools/release.sh <version> [notes-file]
#
#   <version>      e.g. 0.1.0  →  tag v0.1.0, release "SportFit Sync v0.1.0"
#   [notes-file]   markdown with the release notes
#                  (default: docs/releases/release-notes-<version>.md)
#
# What it does, in order:
#   1. refuses to run on a dirty working tree
#   2. builds a signed release APK   (./build.sh :app:assembleRelease)
#   3. verifies the APK signature with apksigner
#   4. creates the annotated tag v<version> and pushes it
#   5. creates the GitHub Release and uploads the APK as an asset
#   6. reads the release back and checks the asset really landed
#
# The GitHub token is taken from the macOS keychain via `git credential fill`,
# so it never appears in this script, in the shell history, or on disk.
#
# Prerequisites:
#   - keystore/signing.properties  (see app/build.gradle.kts for the format)
#   - a GitHub credential for github.com stored in the keychain
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${1:?usage: tools/release.sh <version> [notes-file]}"
NOTES_FILE="${2:-docs/releases/release-notes-${VERSION}.md}"
TAG="v${VERSION}"
TITLE="SportFit Sync ${TAG}"
APK="app/build/outputs/apk/release/app-release.apk"

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------- preflight --
say "preflight"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "working tree is not clean — commit or stash first:" >&2
  git status --short >&2
  exit 1
fi
echo "working tree clean"

if [[ ! -f keystore/signing.properties ]]; then
  echo "keystore/signing.properties is missing — the APK would be unsigned." >&2
  echo "See app/build.gradle.kts for the expected keys." >&2
  exit 1
fi
echo "signing config present"

if [[ -n "$(git tag -l "$TAG")" ]]; then
  echo "tag $TAG already exists — bump the version or delete the tag first." >&2
  exit 1
fi

# -------------------------------------------------------------------- build --
say "building signed release APK"
./build.sh :app:assembleRelease

[[ -f "$APK" ]] || { echo "expected $APK to exist after the build" >&2; exit 1; }

# ------------------------------------------------------------------- verify --
say "verifying APK signature"

APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [[ -z "$APKSIGNER" ]]; then
  echo "apksigner not found under ~/Library/Android/sdk/build-tools" >&2
  exit 1
fi
"$APKSIGNER" verify --print-certs "$APK" | sed 's/^/  /'

SHA256="$(shasum -a 256 "$APK" | awk '{print $1}')"
SIZE="$(stat -f %z "$APK")"
echo "  sha256: $SHA256"
echo "  size  : $SIZE bytes"

# ---------------------------------------------------------------------- tag --
say "tagging $TAG"
git tag -a "$TAG" -m "SportFit Sync $TAG"

say "pushing commit and tag"
for attempt in 1 2 3 4 5 6; do
  if git push origin main --follow-tags; then
    break
  fi
  if [[ $attempt -eq 6 ]]; then
    echo "push failed repeatedly — the tag exists locally; push it with:" >&2
    echo "  git push origin main --follow-tags" >&2
    exit 1
  fi
  echo "  push attempt $attempt failed, retrying..."
  sleep 12
done

# ------------------------------------------------------------------ release --
say "creating GitHub Release"

TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')"
if [[ -z "$TOKEN" ]]; then
  echo "no GitHub credential in the keychain" >&2
  exit 1
fi

API="https://api.github.com/repos/Guo-HT/SportFitSync"

auth=(-H "Authorization: Bearer ${TOKEN}"
      -H "Accept: application/vnd.github+json"
      -H "X-GitHub-Api-Version: 2022-11-28")

# Create the release (idempotent: an existing release for the tag is reused).
BODY=""
if [[ -f "$NOTES_FILE" ]]; then
  BODY="$(python3 -c 'import json,sys; print(json.dumps(open(sys.argv[1], encoding="utf-8").read()))' "$NOTES_FILE")"
else
  echo "  notes file $NOTES_FILE not found — creating the release with an empty body"
  BODY="null"
fi

RELEASE_JSON="$(
  curl -sS --http1.1 --retry 5 --retry-all-errors --retry-delay 8 \
       "${auth[@]}" -X POST -H "Content-Type: application/json" \
       -d "{\"tag_name\":\"${TAG}\",\"name\":\"${TITLE}\",\"body\":${BODY},\"draft\":false,\"prerelease\":false,\"target_commitish\":\"main\"}" \
       "$API/releases" || true
)"

RELEASE_ID="$(printf '%s' "$RELEASE_JSON" | jq -r '.id // empty')"
if [[ -z "$RELEASE_ID" ]]; then
  # A release for this tag may already exist (e.g. a re-run after a partial
  # failure); fetch it instead of failing.
  RELEASE_ID="$(curl -sS --http1.1 --retry 5 --retry-all-errors --retry-delay 8 \
                      "${auth[@]}" "$API/releases/tags/${TAG}" | jq -r '.id // empty')"
fi
[[ -n "$RELEASE_ID" ]] || { echo "could not create or find the release" >&2; printf '%s\n' "$RELEASE_JSON" >&2; exit 1; }
echo "  release id: $RELEASE_ID"

# Drop a stale asset with the same name, if any: GitHub rejects duplicates.
STALE_ID="$(curl -sS --http1.1 --retry 5 --retry-all-errors --retry-delay 8 \
                    "${auth[@]}" "$API/releases/${RELEASE_ID}/assets" \
            | jq -r '.[] | select(.name == "app-release.apk") | .id')"
if [[ -n "$STALE_ID" ]]; then
  echo "  removing stale asset $STALE_ID"
  curl -sS --http1.1 "${auth[@]}" -X DELETE "$API/releases/assets/${STALE_ID}" || true
fi

say "uploading $APK ($SIZE bytes)"
# The upload host is NOT api.github.com. GitHub exposes the right one as the
# release's `upload_url` template, e.g.
#   https://uploads.github.com/repos/OWNER/REPO/releases/ID/assets{?name,label}
# Posting to api.github.com .../assets answers 404 for every request, which
# looks exactly like a missing release but is just the wrong host.
UPLOAD_URL="$(curl -sS --http1.1 --retry 5 --retry-all-errors --retry-delay 8 \
                     "${auth[@]}" "$API/releases/${RELEASE_ID}" \
             | jq -r '.upload_url')"
UPLOAD_URL="${UPLOAD_URL%\{*\}}"
UPLOAD_URL="${UPLOAD_URL%\?*}?name=app-release.apk"
echo "  upload url: $UPLOAD_URL"

# HTTP/1.1 matters: the HTTP/2 stream to the upload host has been observed to
# die with PROTOCOL_ERROR part-way through a ~13 MB upload. The speed-limit
# watchdog aborts a stalled transfer instead of hanging until the timeout.
curl -sS --http1.1 -X POST --upload-file "$APK" \
     "${auth[@]}" \
     -H "Content-Type: application/vnd.android.package-archive" \
     -H "Expect:" \
     --retry 5 --retry-all-errors --retry-delay 10 \
     --speed-time 90 --speed-limit 2000 \
     -o /tmp/sportfit-asset.json \
     -w "  http=%{http_code} uploaded=%{size_upload} time=%{time_total}s speed=%{speed_upload}B/s\n" \
     "$UPLOAD_URL"

UPLOADED="$(jq -r '.size // empty' /tmp/sportfit-asset.json 2>/dev/null)"
if [[ "$UPLOADED" != "$SIZE" ]]; then
  echo "upload reported size ${UPLOADED:-<none>}, expected $SIZE" >&2
  head -c 400 /tmp/sportfit-asset.json >&2
  exit 1
fi

# ------------------------------------------------------------------- verify --
say "verifying the published release"
CHECK="$(curl -sS --http1.1 --retry 5 --retry-all-errors --retry-delay 8 \
                "${auth[@]}" "$API/releases/tags/${TAG}")"

printf '%s' "$CHECK" | jq -r '
  "  tag      : \(.tag_name)",
  "  name     : \(.name)",
  "  draft    : \(.draft)",
  "  url      : \(.html_url)",
  "  assets   : \(.assets | length)"
'
ASSET_SIZE="$(printf '%s' "$CHECK" | jq -r '.assets[]? | select(.name=="app-release.apk") | .size')"
if [[ "$ASSET_SIZE" != "$SIZE" ]]; then
  echo "asset size mismatch: expected $SIZE, got ${ASSET_SIZE:-<missing>}" >&2
  exit 1
fi

say "done"
echo "  $TITLE → https://github.com/Guo-HT/SportFitSync/releases/tag/${TAG}"
echo "  download → https://github.com/Guo-HT/SportFitSync/releases/download/${TAG}/app-release.apk"
echo "  sha256: $SHA256"
echo
echo "  Verify what users actually download with:"
echo "    curl -sSL -o app-release.apk \\"
echo "      https://github.com/Guo-HT/SportFitSync/releases/download/${TAG}/app-release.apk"
echo "    shasum -a 256 app-release.apk   # expect ${SHA256}"
