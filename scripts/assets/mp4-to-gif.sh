#!/usr/bin/env bash
# mp4 (adb screenrecord) -> optimized palette GIF for docs/media + README.
# Usage: mp4-to-gif.sh <in.mp4> <out.gif> [width=360] [fps=12]
set -euo pipefail
IN="${1:?input mp4}"
OUT="${2:?output gif}"
W="${3:-360}"
FPS="${4:-12}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
ffmpeg -y -loglevel error -i "$IN" -vf "fps=$FPS,scale=$W:-2:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=4" "$OUT"
ls -la "$OUT"
