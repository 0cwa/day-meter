#!/usr/bin/env bash
set -euo pipefail

gradle_file="${1:-app/build.gradle}"
if [[ ! -f "$gradle_file" ]]; then
  echo "::error title=Missing Android metadata::Could not read $gradle_file" >&2
  exit 1
fi
version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=?[[:space:]]*"([^"]+)".*/\1/p' "$gradle_file")"
if [[ ! "$version_name" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(\.(0|[1-9][0-9]*))?$ ]]; then
  echo "::error title=Invalid Android versionName::Expected MAJOR.MINOR[.PATCH] in $gradle_file." >&2
  exit 1
fi
printf '%s\n' "$version_name"
