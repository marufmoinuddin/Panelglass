#!/usr/bin/env bash
# Fetch the Qualcomm NPU libraries for the experimental NPU backend.
#
# They are Qualcomm-proprietary binaries, so they are never committed: this script downloads them
# into app/src/main/jniLibs/arm64-v8a/, which .gitignore keeps out of the repository. The URL is
# added to the repository, never the content (see CONTRIBUTING.md).
#
# Source: google-ai-edge/litert-samples, samples/litert/qualcomm/llm_chatbot_npu.
# Idempotent: a file that is already there is not downloaded again.
#
# Usage: scripts/fetch-npu-libs.sh
set -euo pipefail

REPO="google-ai-edge/litert-samples"
DIR="samples/litert/qualcomm/llm_chatbot_npu/app/src/main/jniLibs/arm64-v8a"
API="https://api.github.com/repos/${REPO}/contents/${DIR}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"

if [ ! -d "$DEST" ]; then
  mkdir -p "$DEST"
fi

echo "Fetching the Qualcomm NPU libraries from ${REPO}/${DIR}"

# The listing is JSON; the file names and their download URLs are read from it.
LIST="$(curl -fsSL "$API")"

if ! printf '%s' "$LIST" | grep -q '"download_url"'; then
  echo "Could not read the library listing (is the path still correct?)." >&2
  exit 1
fi

COUNT=0
while IFS= read -r name; do
  [ -n "$name" ] || continue
  url="$(printf '%s' "$LIST" | python3 -c "
import json,sys
name = sys.argv[1]
data = json.load(sys.stdin)
for f in data:
    if f.get('name') == name:
        print(f.get('download_url',''))
        break
" "$name")"

  if [ -z "$url" ]; then
    echo "  ! no download URL for $name" >&2
    exit 1
  fi

  if [ -f "$DEST/$name" ]; then
    echo "  = $name (already there)"
  else
    echo "  + $name"
    curl -fsSL "$url" -o "$DEST/$name.part"
    mv "$DEST/$name.part" "$DEST/$name"
  fi
  COUNT=$((COUNT + 1))
done < <(printf '%s' "$LIST" | python3 -c "
import json,sys
data = json.load(sys.stdin)
for f in data:
    if f.get('type') == 'file':
        print(f.get('name',''))
")

if [ "$COUNT" -eq 0 ]; then
  echo "The directory held no files; the NPU backend cannot be built from it." >&2
  exit 1
fi

echo
echo "In app/src/main/jniLibs/arm64-v8a/:"
ls -lh "$DEST" | tail -n +2 | awk '{printf "  %-40s %s\n", $9, $5}'
echo
du -sh "$DEST"
echo "These files are Qualcomm-licensed and are not committed to git."
