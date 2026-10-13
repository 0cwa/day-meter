#!/usr/bin/env bash
set -euo pipefail

# Public GitHub release versions may be vMAJOR.MINOR or vMAJOR.MINOR.PATCH.
# Android versionName is set from the tag at build time (separately from
# the monotonic numeric Android versionCode).
tag="${1:-}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
  echo "::error title=Invalid release tag::Expected vMAJOR.MINOR or vMAJOR.MINOR.PATCH (for example v1.1), got '$tag'." >&2
  exit 1
fi

# Optional second argument enforces the version embedded in the Android build.
# Only use this after the tagged build sources have been prepared for release.
if [[ -n "${2:-}" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  version_name="$(bash "$script_dir/read_version_name.sh" "$2")"
  if [[ "$tag" != "v$version_name" ]]; then
    echo "::error title=Tag/app version mismatch::Tag '$tag' does not match $2 versionName '$version_name'." >&2
    exit 1
  fi
fi

echo "Validated release tag $tag."
