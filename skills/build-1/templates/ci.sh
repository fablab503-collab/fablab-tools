#!/bin/bash
# Usage: ci.sh status <sha>      -> prints runs for the commit (workflow, status, conclusion, id, url)
#        ci.sh wait <sha>        -> polls until all runs for the sha complete (max ~25 min), then prints status
#        ci.sh logs <run_id>     -> prints failed steps' log tails for the run
set -u
REPO=fablab503-collab/fablab-tools
H="Authorization: Bearer $(gh auth token)"
api() { curl -sS -H "$H" -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/$1"; }
case "$1" in
  status)
    api "actions/runs?head_sha=$2&per_page=10" | python3 -c '
import json,sys
d=json.load(sys.stdin)
for r in d.get("workflow_runs",[]):
    print("%-28s %-12s %-10s id=%s %s" % (r["name"], r["status"], str(r["conclusion"]), r["id"], r["html_url"]))
print("runs:", d.get("total_count"))' ;;
  wait)
    for i in $(seq 1 100); do
      out=$(api "actions/runs?head_sha=$2&per_page=10")
      total=$(echo "$out" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("total_count",0))')
      pending=$(echo "$out" | python3 -c 'import json,sys; print(sum(1 for r in json.load(sys.stdin).get("workflow_runs",[]) if r["status"]!="completed"))')
      if [ "$total" -gt 0 ] && [ "$pending" -eq 0 ]; then break; fi
      sleep 15
    done
    "$0" status "$2" ;;
  logs)
    api "actions/runs/$2/jobs" | python3 -c '
import json,sys
for j in json.load(sys.stdin)["jobs"]:
    print("JOB %s: %s/%s id=%s" % (j["name"], j["status"], j["conclusion"], j["id"]))
    for s in j["steps"]:
        print("   step %2d %-9s %s" % (s["number"], str(s["conclusion"]), s["name"]))'
    tmp=$(mktemp -d "${TMPDIR:-/tmp}/cilogs.XXXX")
    curl -sS -L -H "$H" -o "$tmp/logs.zip" "https://api.github.com/repos/$REPO/actions/runs/$2/logs"
    (cd "$tmp" && unzip -q logs.zip 2>/dev/null)
    echo "LOG FILES in $tmp:"; find "$tmp" -name '*.txt' | sort | head -40 ;;
  *) echo "usage: ci.sh status|wait|logs ..."; exit 1 ;;
esac
