#!/bin/bash
# Runs the benchmark for several configurations one after the other.
#   run-all.sh <suffix> <label=extension folder|none> ...
# example: run-all.sh 0928 none=none v120=../../build/bench-ext/v120
#   -> results/<label>-train-<suffix>.jsonl (the 100 sites) and results/<label>-heldout-<suffix>.jsonl (60 other sites)
set -u
cd "$(dirname "$0")"
SUFFIX="$1"; shift
for set in train heldout; do
  SITES=../safari-sites/sites.tsv
  [ "$set" = heldout ] && SITES=sites-heldout.tsv
  for spec in "$@"; do
    label="${spec%%=*}"; ext="${spec#*=}"
    echo "$(date +%H:%M:%S) $label $set" >> results/run-all-$SUFFIX.status
    ./run.sh "$label-$set-$SUFFIX" "$ext" "$SITES" "${PARALLEL:-3}"
  done
done
echo "$(date +%H:%M:%S) DONE" >> results/run-all-$SUFFIX.status
