#!/usr/bin/env bash
set -euo pipefail

# Only a version that is actually built into the APK may become a release.
tag="${1:-}"
if [[ ! "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
  echo "::error title=Invalid release tag::Expected vMAJOR.MINOR.PATCH (for example v1.0.36), got '$tag'."
  exit 1
fi

version_name="$(bash tools/read_version_name.sh)"
if [[ "$tag" != "v$version_name" ]]; then
  echo "::error title=Tag/app version mismatch::Tag '$tag' does not match app/build.gradle versionName '$version_name'. Merge an updated versionName/versionCode to main first, then create tag 'v$version_name' on that commit. Never rename or move a published tag."
  exit 1
fi

echo "Release tag $tag matches Android versionName $version_name."
