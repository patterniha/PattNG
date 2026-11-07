#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Instead of building the Aether core from source, ship the prebuilt core from a
# l3p8/Aether release-workflow artifact. Only android arm64-v8a is kept; the other
# ABIs and the tor/psiphon builds are no longer produced.
if [[ -z "${GH_TOKEN:-}" ]]; then
  echo "GH_TOKEN is required to download the Aether artifact" >&2
  exit 1
fi

# Override with AETHER_ARTIFACT_URL to fetch a different artifact (e.g. a newer run).
AETHER_ARTIFACT_URL="${AETHER_ARTIFACT_URL:-https://api.github.com/repos/l3p8/Aether/actions/artifacts/13520736912/zip}"

# GitHub serves every artifact through its /zip endpoint, so the download is a zip
# whose single member is the originally-uploaded aether-android-arm64.tar.gz.
OUT_DIR="$__dir/libs-aether/arm64-v8a"
mkdir -p "$OUT_DIR"

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

echo "[aether] downloading the prebuilt core from $AETHER_ARTIFACT_URL"
curl -fsSL -H "Authorization: Bearer $GH_TOKEN" \
     -H "Accept: application/vnd.github+json" \
     -o "$TMPDIR/artifact.zip" "$AETHER_ARTIFACT_URL"

echo "[aether] extracting the android arm64 core"
python3 -c "import zipfile; zipfile.ZipFile('$TMPDIR/artifact.zip').extractall('$TMPDIR/artifact')"
TARBALL="$(find "$TMPDIR/artifact" -name '*.tar.gz' | head -1)"
if [[ -z "$TARBALL" ]]; then
  echo "no tarball found inside the Aether artifact" >&2
  exit 1
fi
tar xzf "$TARBALL" -C "$TMPDIR" aether

if [[ ! -f "$TMPDIR/aether" ]]; then
  echo "aether binary not found in the artifact" >&2
  exit 1
fi

cp "$TMPDIR/aether" "$OUT_DIR/libaether.so"
chmod +x "$OUT_DIR/libaether.so"

echo "[aether] staged:"
ls -la "$OUT_DIR/libaether.so"
