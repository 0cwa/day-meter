#!/usr/bin/env bash
set -euo pipefail

# Publish only complete, digest-verified artifacts. A rerun may repair an
# incomplete release but must never overwrite an existing (possibly immutable)
# published asset with different bytes.
tag="${1:?Usage: bash tools/publish_release.sh vX.Y.Z [dist-directory]}"
dist="${2:-dist}"
repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
bash tools/check_release_tag.sh "$tag"

apk="day-meter-${tag}.apk"
bundle="day-meter-${tag}.aab"
checksums="day-meter-${tag}-SHA256SUMS.txt"
names=("$apk" "$bundle" "$checksums")

for name in "${names[@]}"; do
  if [[ ! -s "$dist/$name" ]]; then
    echo "::error title=Missing release artifact::Expected nonempty file $dist/$name."
    exit 1
  fi
done
(cd "$dist" && sha256sum -c "$checksums")

if gh release view "$tag" --repo "$repo" --json isDraft >/dev/null 2>&1; then
  echo "Using existing release for $tag; verifying existing assets before any upload."
else
  # Do not publish an empty release: upload and verify in draft state first.
  gh release create "$tag" --repo "$repo" --draft --verify-tag \
    --title "Day Meter ${tag#v}" --generate-notes
fi

# Drafts are absent from the tag endpoint. Resolve their stable API URL through
# the CLI, which can locate both draft and published releases by tag.
release_api_url="$(gh release view "$tag" --repo "$repo" --json apiUrl --jq '.apiUrl')"
assets="$(gh api "$release_api_url" --jq '.assets')"

# Never clobber an asset: compare the GitHub-computed digest before uploading.
for name in "${names[@]}"; do
  count="$(jq --arg name "$name" '[.[] | select(.name == $name)] | length' <<<"$assets")"
  if (( count > 1 )); then
    echo "::error::Duplicate asset $name on $tag."
    exit 1
  fi
  if (( count == 1 )); then
    expected="sha256:$(sha256sum "$dist/$name" | cut -d' ' -f1)"
    found="$(jq -r --arg name "$name" '.[] | select(.name == $name) | .digest // ""' <<<"$assets")"
    if [[ "$found" != "$expected" ]]; then
      echo "::error title=Release asset conflict::$name exists with digest $found, expected $expected. No asset was overwritten."
      exit 1
    fi
  fi
done

for name in "${names[@]}"; do
  count="$(jq --arg name "$name" '[.[] | select(.name == $name)] | length' <<<"$assets")"
  if (( count == 0 )); then
    gh release upload "$tag" "$dist/$name" --repo "$repo"
  fi
done

# GitHub supplies an immutable SHA-256 digest calculated from uploaded bytes.
assets="$(gh api "$release_api_url" --jq '.assets')"
if [[ "$(jq 'length' <<<"$assets")" -ne "${#names[@]}" ]]; then
  echo "::error::Expected exactly ${#names[@]} release files on $tag."
  exit 1
fi
for name in "${names[@]}"; do
  expected="sha256:$(sha256sum "$dist/$name" | cut -d' ' -f1)"
  if ! jq -e --arg name "$name" --arg digest "$expected" '
    [.[] | select(.name == $name and .digest == $digest
      and .state == "uploaded" and .size > 0)] | length == 1
  ' <<<"$assets" >/dev/null; then
    echo "::error::Remote upload missing or digest mismatch for $name; release not published."
    exit 1
  fi
done

if [[ "$(gh release view "$tag" --repo "$repo" --json isDraft --jq '.isDraft')" == "true" ]]; then
  gh release edit "$tag" --repo "$repo" --draft=false
fi

if [[ "$(gh release view "$tag" --repo "$repo" --json isDraft --jq '.isDraft')" != "false" ]]; then
  echo "::error::Release $tag remained a draft."
  exit 1
fi

echo "Verified published release: https://github.com/$repo/releases/tag/$tag"
