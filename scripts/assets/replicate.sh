#!/usr/bin/env bash
# Thin Replicate runner (brief 04 art pipeline; conventions: docs/design/icons.md §models).
# Usage: replicate.sh <model> <output-path> <prompt>
#   model: e.g. openai/gpt-image-2 | black-forest-labs/flux-schnell
# The API token is read from $REPLICATE_API_TOKEN or the sibling AI project's .env —
# it is NEVER committed or echoed.
set -euo pipefail

MODEL="${1:?model required}"
OUT="${2:?output path required}"
PROMPT="${3:?prompt required}"

if [[ -z "${REPLICATE_API_TOKEN:-}" ]]; then
  ENV_FILE="$HOME/Documents/Projects/ai-image-transformer/.env"
  if [[ -f "$ENV_FILE" ]]; then
    REPLICATE_API_TOKEN="$(grep -oE 'r8_[A-Za-z0-9]+' "$ENV_FILE" | head -1)"
  fi
fi
[[ -n "${REPLICATE_API_TOKEN:-}" ]] || { echo "no Replicate token found" >&2; exit 1; }

API="https://api.replicate.com/v1"
AUTH="Authorization: Bearer $REPLICATE_API_TOKEN"

# gpt-image-2 has no transparent-background support (icons.md gotcha): prompt pins a
# uniform #808080 canvas; scripts/assets/key-gray.py keys it out afterwards.
if [[ "$MODEL" == openai/gpt-image-2 ]]; then
  INPUT_JSON=$(python3 -c 'import json,sys;print(json.dumps({"prompt":sys.argv[1],"aspect_ratio":"1:1","output_format":"png","quality":"medium"}))' "$PROMPT")
else
  INPUT_JSON=$(python3 -c 'import json,sys;print(json.dumps({"prompt":sys.argv[1],"aspect_ratio":"1:1","output_format":"png"}))' "$PROMPT")
fi

CREATE=$(curl -sf -X POST "$API/models/$MODEL/predictions" -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"input\": $INPUT_JSON}")
PRED_ID=$(echo "$CREATE" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

for _ in $(seq 1 60); do
  STATUS=$(curl -sf "$API/predictions/$PRED_ID" -H "$AUTH")
  S=$(echo "$STATUS" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')
  case "$S" in
    succeeded)
      URL=$(echo "$STATUS" | python3 -c 'import sys,json;o=json.load(sys.stdin)["output"];print(o if isinstance(o,str) else o[0])')
      mkdir -p "$(dirname "$OUT")"
      curl -sfL "$URL" -o "$OUT"
      echo "saved: $OUT"
      exit 0
      ;;
    failed|canceled)
      echo "prediction $S: $STATUS" >&2
      exit 1
      ;;
  esac
  sleep 5
done
echo "timed out waiting for $PRED_ID" >&2
exit 1
