#!/usr/bin/env bash
set -euo pipefail

# All release regression checks run on PRs without signing secrets or write access.
bash -n tools/check_release_tag.sh tools/prepare_release_source.sh \
  tools/publish_release.sh tools/check_release_health.sh tools/test_release_publish.sh

version="$(bash tools/read_version_name.sh)"
bash tools/check_release_tag.sh "v$version" app/build.gradle
bash tools/check_release_tag.sh v1.1
bash tools/check_release_tag.sh v1.1.0

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for bad_tag in v1 v1. v1.1. v01.1 v1.1.0.0; do
  if bash tools/check_release_tag.sh "$bad_tag" >"$tmp/output" 2>&1; then
    echo "Malformed release tag was accepted: $bad_tag" >&2
    exit 1
  fi
  grep -q 'Invalid release tag' "$tmp/output"
done

# Two-component tags are accepted but the APK must actually embed that version.
if bash tools/check_release_tag.sh v1.1 app/build.gradle >"$tmp/output" 2>&1; then
  echo "Unprepared Android versionName mismatch was accepted" >&2
  exit 1
fi
grep -q 'Tag/app version mismatch' "$tmp/output"

# Production build edits only a temporary copy of the Gradle metadata.
cp app/build.gradle "$tmp/build.gradle"
bash tools/prepare_release_source.sh v1.1 "$tmp/build.gradle"
[[ "$(bash tools/read_version_name.sh "$tmp/build.gradle")" == "1.1" ]]
grep -Eq '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*36([[:space:]]|$)' "$tmp/build.gradle"
bash tools/check_release_tag.sh v1.1 "$tmp/build.gradle"
bash tools/prepare_release_source.sh v1.1.0 "$tmp/build.gradle"
[[ "$(bash tools/read_version_name.sh "$tmp/build.gradle")" == "1.1.0" ]]
bash tools/check_release_tag.sh v1.1.0 "$tmp/build.gradle"
[[ "$(bash tools/read_version_name.sh)" == "$version" ]]

bash tools/test_release_publish.sh

# Fake read-only GitHub API verifies monitor behavior without any network access.
mkdir -p "$tmp/bin"
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == api && "$2" == --paginate ]] || exit 2
cat "$MOCK_RELEASES_FILE"
MOCK
chmod +x "$tmp/bin/gh"
export PATH="$tmp/bin:$PATH" GITHUB_REPOSITORY="0cwa/day-meter"
export MOCK_RELEASES_FILE="$tmp/releases.json"
digest="sha256:$(printf 'a%.0s' {1..64})"

jq -n --arg tag v1.1 --arg digest "$digest" '
  [{tag_name:$tag, draft:false, assets:[
    {name:("day-meter-" + $tag + ".apk"), size:100, state:"uploaded", digest:$digest},
    {name:("day-meter-" + $tag + ".aab"), size:100, state:"uploaded", digest:$digest},
    {name:("day-meter-" + $tag + "-SHA256SUMS.txt"), size:100, state:"uploaded", digest:$digest}
  ]}]
' > "$MOCK_RELEASES_FILE"

RELEASE_EXPECTED_TAG=v1.1 bash tools/check_release_health.sh >"$tmp/output"
grep -q '0 problem(s)' "$tmp/output"

jq '.[0].assets |= .[:2]' "$MOCK_RELEASES_FILE" >"$tmp/incomplete.json"
if MOCK_RELEASES_FILE="$tmp/incomplete.json" bash tools/check_release_health.sh >"$tmp/output" 2>&1; then
  echo "Monitor accepted a release without checksums" >&2
  exit 1
fi
grep -q 'Incomplete GitHub Release' "$tmp/output"

jq '.[0].tag_name = "v1"' "$MOCK_RELEASES_FILE" >"$tmp/invalid-tag.json"
if MOCK_RELEASES_FILE="$tmp/invalid-tag.json" bash tools/check_release_health.sh >"$tmp/output" 2>&1; then
  echo "Monitor accepted malformed published release tag" >&2
  exit 1
fi
grep -q 'Invalid published release' "$tmp/output"

# A valid tag without any release is an alert (even when no workflow was run).
printf '%s\n' '[]' >"$tmp/no-releases.json"
if RELEASE_EXPECTED_TAG=v1.1 MOCK_RELEASES_FILE="$tmp/no-releases.json" \
    bash tools/check_release_health.sh >"$tmp/output" 2>&1; then
  echo "Monitor missed a version tag with no published release" >&2
  exit 1
fi
grep -q 'Tag without published release' "$tmp/output"

echo "Release version, packaging, publication and health regression checks passed."
