#!/usr/bin/env bash
set -euo pipefail

# Release regression checks run on every PR without signing keys or GitHub API.
bash -n tools/check_release_tag.sh tools/publish_release.sh tools/check_release_health.sh tools/test_release_publish.sh
version="$(bash tools/read_version_name.sh)"
bash tools/check_release_tag.sh "v$version"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if bash tools/check_release_tag.sh v1.1 >"$tmp/output" 2>&1; then
  echo "Invalid two-component tag was accepted" >&2
  exit 1
fi
grep -q 'Invalid release tag' "$tmp/output"

bad_tag="v${version%.*}.99999999"
if bash tools/check_release_tag.sh "$bad_tag" >"$tmp/output" 2>&1; then
  echo "Mismatched version tag was accepted" >&2
  exit 1
fi
grep -q 'Tag/app version mismatch' "$tmp/output"

bash tools/test_release_publish.sh

# A fake gh command verifies that the watchdog detects incomplete releases
# and does not depend on network access in unit tests.
mkdir -p "$tmp/bin"
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api ]] || exit 2
if [[ "$2" == --paginate ]]; then
  cat "$MOCK_RELEASES_FILE"
elif [[ "$2" == repos/*/git/ref/tags/* ]]; then
  [[ "${MOCK_TAG_EXISTS:-false}" == true ]] || exit 1
  printf '%s\\n' '{}'
else
  exit 2
fi
MOCK
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY="0cwa/day-meter"
export MOCK_RELEASES_FILE="$tmp/releases.json"
digest="sha256:$(printf 'a%.0s' {1..64})"

jq -n --arg tag "v$version" --arg digest "$digest" '
  [{tag_name:$tag, draft:false, assets:[
    {name:("day-meter-" + $tag + ".apk"), size:100, state:"uploaded", digest:$digest},
    {name:("day-meter-" + $tag + ".aab"), size:100, state:"uploaded", digest:$digest},
    {name:("day-meter-" + $tag + "-SHA256SUMS.txt"), size:100, state:"uploaded", digest:$digest}
  ]}]
' > "$MOCK_RELEASES_FILE"

bash tools/check_release_health.sh >"$tmp/output"
grep -q '0 problem(s)' "$tmp/output"

jq '.[0].assets |= .[:2]' "$MOCK_RELEASES_FILE" >"$tmp/incomplete.json"
MOCK_RELEASES_FILE="$tmp/incomplete.json" bash tools/check_release_health.sh >"$tmp/output" 2>&1 && {
  echo "Health monitor accepted a release without checksums" >&2
  exit 1
}
grep -q 'Incomplete GitHub Release' "$tmp/output"

jq '.[0].tag_name = "v1.1"' "$MOCK_RELEASES_FILE" >"$tmp/invalid-tag.json"
MOCK_RELEASES_FILE="$tmp/invalid-tag.json" bash tools/check_release_health.sh >"$tmp/output" 2>&1 && {
  echo "Health monitor accepted malformed release version" >&2
  exit 1
}
grep -q 'Invalid published release' "$tmp/output"

# A current app-version tag with no published release must also be detected.
printf '%s\\n' '[]' > "$tmp/no-releases.json"
if MOCK_TAG_EXISTS=true MOCK_RELEASES_FILE="$tmp/no-releases.json" \\
    bash tools/check_release_health.sh >"$tmp/output" 2>&1; then
  echo "Health monitor missed a version tag with no published release" >&2
  exit 1
fi
grep -q 'Tag without published release' "$tmp/output"

echo "Release version and health monitor regression checks passed."
