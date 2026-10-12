#!/usr/bin/env bash
set -euo pipefail

# Git tags are immutable release inputs. Build from the tagged commit, but
# derive Android's user-visible versionName from the GitHub release tag.
# Only versionName is adjusted in the CI working tree; no commit/tag is moved.
tag="${1:?Usage: bash tools/prepare_release_source.sh vMAJOR.MINOR[.PATCH] path/to/app/build.gradle}"
gradle_file="${2:?Gradle file path is required}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$script_dir/check_release_tag.sh" "$tag"
old_version="$(bash "$script_dir/read_version_name.sh" "$gradle_file")"
new_version="${tag#v}"

# Android update ordering comes from numeric versionCode, not versionName.
version_codes="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=?[[:space:]]*([0-9]+).*/\1/p' "$gradle_file")"
if [[ ! "$version_codes" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error title=Invalid Android versionCode::Expected a single positive numeric versionCode in $gradle_file." >&2
  exit 1
fi

# Fail closed if the Gradle DSL changes: do not patch a file with multiple,
# missing, or dynamically computed versionName properties.
python3 - "$gradle_file" "$new_version" <<'PY'
from pathlib import Path
import re
import sys

gradle_file = Path(sys.argv[1])
release_version_name = sys.argv[2]
source = gradle_file.read_text(encoding="utf-8")
pattern = re.compile(r'(?m)^([ \t]*versionName[ \t]*=[ \t]*")[^"]+(".*)$')
matches = list(pattern.finditer(source))
if len(matches) != 1:
    raise SystemExit("Expected exactly one literal versionName assignment in " + str(gradle_file))
prepared = pattern.sub(lambda match: match.group(1) + release_version_name + match.group(2), source, count=1)
if prepared != source:
    gradle_file.write_text(prepared, encoding="utf-8")
PY
bash "$script_dir/check_release_tag.sh" "$tag" "$gradle_file"

echo "Release $tag: Android versionName $old_version -> $new_version; versionCode $version_codes (unchanged)."
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Android release version metadata"
    echo
    echo "- GitHub release tag: `$tag`"
    echo "- Tagged commit's original Android versionName: `$old_version`"
    echo "- Release APK/AAB versionName: `$new_version`"
    echo "- Android versionCode (unchanged from tagged source): `$version_codes`"
    echo
    echo "Only the build working tree's versionName is adjusted; the tag and its source commit are unchanged."
  } >> "$GITHUB_STEP_SUMMARY"
fi
