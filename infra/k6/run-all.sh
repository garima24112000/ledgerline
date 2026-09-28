#!/usr/bin/env bash
# Runs steady, spike and duplicate_storm one after another and saves their summaries in
# results/<UTC timestamp>/ (next to this script):
#   <scenario>.md    Markdown summary, ready to paste into a README
#   <scenario>.json  k6's full summary data
#   <scenario>.log   k6's console output
#   summary.md       the three Markdown summaries in one file
#
# Required: BASE_URL, API_KEYS, ADMIN_USERNAME, ADMIN_PASSWORD (see README.md).
# Optional: ENV_LABEL, COOLDOWN (seconds between scenarios, default 30), and any scenario variable.
#
# A scenario that fails its thresholds doesn't stop the others; the script exits 1 at the end if
# any scenario failed.
set -uo pipefail

: "${BASE_URL:?BASE_URL is required}"
: "${API_KEYS:?API_KEYS is required}"
: "${ADMIN_USERNAME:?ADMIN_USERNAME is required}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"
COOLDOWN="${COOLDOWN:-30}"

cd "$(dirname "$0")"
RESULTS_DIR="results/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
mkdir -p "$RESULTS_DIR"
export RESULTS_DIR

scenarios=(steady spike duplicate_storm)
failed=()
for i in "${!scenarios[@]}"; do
  scenario="${scenarios[$i]}"
  if [ "$i" -gt 0 ]; then
    # Let the previous scenario's backlog drain, so it doesn't count against the next one.
    echo "--- cooling down for ${COOLDOWN}s"
    sleep "$COOLDOWN"
  fi
  echo "--- running $scenario.js"
  k6 run "$scenario.js" 2>&1 | tee "$RESULTS_DIR/$scenario.log"
  status=${PIPESTATUS[0]}
  if [ "$status" -ne 0 ]; then
    echo "--- $scenario.js exited with $status (99 = thresholds failed)"
    failed+=("$scenario")
  fi
done

for scenario in "${scenarios[@]}"; do
  if [ -f "$RESULTS_DIR/$scenario.md" ]; then
    cat "$RESULTS_DIR/$scenario.md"
  else
    printf '### %s\n\nNo summary: the run did not complete. See %s.log.\n' "$scenario" "$scenario"
  fi
  echo
done > "$RESULTS_DIR/summary.md"

echo "--- summaries saved in infra/k6/$RESULTS_DIR"
if [ "${#failed[@]}" -gt 0 ]; then
  echo "--- failed: ${failed[*]}"
  exit 1
fi
