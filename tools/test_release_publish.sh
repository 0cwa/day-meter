#!/usr/bin/env bash
set -euo pipefail

# Exercise the real release publication script against a stateful fake gh CLI.
# No secrets, GitHub tokens, or release mutations are used in these tests.
# Exercise the existing v1.1 release, not only three-component release names.
tag="v1.1"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/dist"
printf 'signed APK fixture\n' > "$tmp/dist/day-meter-${tag}.apk"
printf 'signed AAB fixture\n' > "$tmp/dist/day-meter-${tag}.aab"
(cd "$tmp/dist" && sha256sum --binary "day-meter-${tag}.apk" "day-meter-${tag}.aab" > "day-meter-${tag}-SHA256SUMS.txt")

cat >"$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
case "$1:$2" in
  release:view)
    [[ -s "$GH_MOCK_STATE" ]] || exit 1
    if [[ " $* " == *" --jq "* ]]; then
      case "${@: -1}" in
        .apiUrl) printf '%s\n' 'repos/0cwa/day-meter/releases/123' ;;
        .isDraft) jq -r '.draft' "$GH_MOCK_STATE" ;;
        *) exit 2 ;;
      esac
    else
      jq '{isDraft:.draft}' "$GH_MOCK_STATE"
    fi
    ;;
  release:create)
    [[ ! -e "$GH_MOCK_STATE" ]] || exit 1
    printf '%s\n' '{"draft":true,"assets":[]}' > "$GH_MOCK_STATE"
    ;;
  release:upload)
    file="$4"
    name="$(basename "$file")"
    digest="sha256:$(sha256sum "$file" | cut -d' ' -f1)"
    size="$(stat -c%s "$file")"
    jq --arg name "$name" --arg digest "$digest" --argjson size "$size" \
      '.assets += [{name:$name, digest:$digest, size:$size, state:"uploaded"}]' \
      "$GH_MOCK_STATE" > "$GH_MOCK_STATE.next"
    mv "$GH_MOCK_STATE.next" "$GH_MOCK_STATE"
    printf '%s\n' "$name" >> "$GH_MOCK_UPLOADS"
    ;;
  release:edit)
    jq '.draft = false' "$GH_MOCK_STATE" > "$GH_MOCK_STATE.next"
    mv "$GH_MOCK_STATE.next" "$GH_MOCK_STATE"
    ;;
  api:repos/*)
    # GitHub's tag endpoint cannot resolve a draft; require its stable ID.
    if [[ "$2" == */releases/tags/* ]]; then
      echo "gh: Not Found (HTTP 404)" >&2
      exit 1
    fi
    [[ "$2" == repos/0cwa/day-meter/releases/123 ]] || exit 2
    [[ "$3" == --jq && "$4" == .assets ]] || exit 2
    jq '.assets' "$GH_MOCK_STATE"
    ;;
  *)
    echo "Unexpected mock gh call: $*" >&2
    exit 2
    ;;
esac
MOCK
chmod +x "$tmp/bin/gh"
export GITHUB_REPOSITORY="0cwa/day-meter"
export GH_MOCK_STATE="$tmp/release.json" GH_MOCK_UPLOADS="$tmp/uploads"
export PATH="$tmp/bin:$PATH"

# First publication: draft, upload exactly three files, verify and publish.
bash tools/publish_release.sh "$tag" "$tmp/dist"
[[ "$(jq -r '.draft' "$GH_MOCK_STATE")" == false ]]
[[ "$(jq '.assets | length' "$GH_MOCK_STATE")" == 3 ]]
[[ "$(wc -l < "$GH_MOCK_UPLOADS")" == 3 ]]

# An identical rerun must leave the published assets untouched.
bash tools/publish_release.sh "$tag" "$tmp/dist"
[[ "$(wc -l < "$GH_MOCK_UPLOADS")" == 3 ]]

# A partly published release can be repaired by uploading just the missing file.
jq '.assets |= .[:2]' "$GH_MOCK_STATE" > "$GH_MOCK_STATE.next"
mv "$GH_MOCK_STATE.next" "$GH_MOCK_STATE"
bash tools/publish_release.sh "$tag" "$tmp/dist"
[[ "$(wc -l < "$GH_MOCK_UPLOADS")" == 4 ]]
[[ "$(jq '.assets | length' "$GH_MOCK_STATE")" == 3 ]]

# A mismatched existing binary must fail, never clobber or upload anything.
jq '.assets[0].digest = "sha256:conflict"' "$GH_MOCK_STATE" > "$GH_MOCK_STATE.next"
mv "$GH_MOCK_STATE.next" "$GH_MOCK_STATE"
if bash tools/publish_release.sh "$tag" "$tmp/dist" >"$tmp/conflict-log" 2>&1; then
  echo "Publisher overwrote a conflicting asset" >&2
  exit 1
fi
grep -q 'Release asset conflict' "$tmp/conflict-log"
[[ "$(wc -l < "$GH_MOCK_UPLOADS")" == 4 ]]

echo "Release publication and idempotent retry tests passed."
