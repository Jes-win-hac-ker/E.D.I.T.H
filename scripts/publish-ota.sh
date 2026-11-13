#!/usr/bin/env bash
# Usage: ./scripts/publish-ota.sh <versionCode> <versionName> [github-repo]
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <versionCode> <versionName> [owner/repo]" >&2
  exit 1
fi

version_code="$1"
version_name="$2"
repo="${3:-Jes-win-hac-ker/E.D.I.T.H}"
tag="v${version_name}+${version_code}"
apk_path="app/build/outputs/apk/debug/app-debug.apk"

./gradlew assembleDebug \
  -Pandroid.injected.version.code="$version_code" \
  -Pandroid.injected.version.name="$version_name"

if [[ ! -f "$apk_path" ]]; then
  echo "Missing APK at $apk_path" >&2
  exit 1
fi

sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
notes="$(printf 'versionCode: %s\nversionName: %s\nsha256: %s\n' "$version_code" "$version_name" "$sha256")"

gh release create "$tag" "$apk_path" \
  --repo "$repo" \
  --title "E.D.I.T.H $version_name ($version_code)" \
  --notes "$notes"
