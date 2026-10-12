#!/usr/bin/env bash
set -euo pipefail

# Independently detect empty/manual releases and missing or corrupt uploads.
# GH_TOKEN needs only repository read permission.
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
releases="$(mktemp)"
trap 'rm -f "$releases"' EXIT

# GitHub returns an array per page; -s combines the pages into a single array.
gh api --paginate "repos/$repo/releases?per_page=100" | jq -s 'add // []' > "$releases"
failures=0
checked=0

# A tag may be pushed without a workflow run (for example by another workflow
# using GITHUB_TOKEN). Detect a missing release for the current app version,
# without treating historical tags inherited from upstream as new releases.
expected_tag="v$(bash tools/read_version_name.sh)"
if gh api "repos/$repo/git/ref/tags/$expected_tag" >/dev/null 2>&1; then
  if ! jq -e --arg tag "$expected_tag" '
    [.[] | select(.tag_name == $tag and .draft == false)] | length == 1
  ' "$releases" >/dev/null; then
    echo "::error title=Tag without published release::Tag '$expected_tag' exists, but no published GitHub Release was found. Check the release workflow."
    failures=$((failures + 1))
  fi
fi

while IFS= read -r release; do
  tag="$(jq -r '.tag_name' <<<"$release")"
  checked=$((checked + 1))

  if [[ ! "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]]; then
    echo "::error title=Invalid published release::Published tag '$tag' is not vMAJOR.MINOR.PATCH."
    failures=$((failures + 1))
  fi

  for name in "day-meter-${tag}.apk" "day-meter-${tag}.aab" "day-meter-${tag}-SHA256SUMS.txt"; do
    if ! jq -e --arg name "$name" '
      [.assets[] | select(.name == $name and .state == "uploaded"
        and .size > 0 and (.digest // "" | test("^sha256:[0-9a-f]{64}$")))] | length == 1
    ' <<<"$release" >/dev/null; then
      echo "::error title=Incomplete GitHub Release::$tag is missing a nonempty, checksummed asset '$name'."
      failures=$((failures + 1))
    fi
  done
done < <(jq -c '.[] | select(.draft == false)' "$releases")

echo "Checked $checked published GitHub release(s); $failures problem(s)."
if (( failures > 0 )); then
  exit 1
fi
