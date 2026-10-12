#!/usr/bin/env bash
set -euo pipefail

# These checks run on every PR, without a keystore or GitHub release token.
bash -n tools/check_release_tag.sh tools/publish_release.sh tools/check_release_health.sh
version="$(bash tools/read_version_name.sh)"
bash tools/check_release_tag.sh "v$version"

output="$(mktemp)"
trap 'rm -f "$output"' EXIT

if bash tools/check_release_tag.sh v1.1 >"$output" 2>&1; then
  echo "Invalid two-component tag was accepted" >&2
  exit 1
fi
grep -q 'Invalid release tag' "$output"

bad_tag="v${version%.*}.99999999"
if bash tools/check_release_tag.sh "$bad_tag" >"$output" 2>&1; then
  echo "Mismatched version tag was accepted" >&2
  exit 1
fi
grep -q 'Tag/app version mismatch' "$output"

echo "Release version contract tests passed."
