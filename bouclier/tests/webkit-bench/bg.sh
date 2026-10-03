#!/bin/bash
# Starts a long command (a 100-site scan, the efficacy check, PopupShot) fully detached: its own
# session, no terminal, input from /dev/null, output appended to a log file. A remote command channel
# (osascript from Claude, an SSH session) that ends or times out then cannot stop it. Prints its PID;
# when it ends, "EXIT <code> <time>" is appended to the log.
#
# usage: tests/webkit-bench/bg.sh <log file> <command> [arguments...]
#   e.g. tests/webkit-bench/bg.sh /tmp/efficacy.log tests/webkit-bench/efficacy.sh --scan
#   then: tail /tmp/efficacy.log (or: pgrep -f efficacy.sh)
set -euo pipefail
[ $# -ge 2 ] || { sed -n '2,9p' "$0"; exit 64; }
exec /usr/bin/python3 - "$@" <<'PY'
import os, subprocess, sys, time
log, cmd = os.path.abspath(sys.argv[1]), sys.argv[2:]
if os.fork() > 0:          # the caller gets its answer at once
    os._exit(0)
os.setsid()                # a new session: no terminal, outside the caller's process group
r, w = os.pipe()
if os.fork() > 0:          # report the runner's PID, then leave
    os.close(w)
    print(os.read(r, 32).decode().strip(), flush=True)
    os._exit(0)
os.close(r)
null = os.open(os.devnull, os.O_RDONLY)
out = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o644)
os.dup2(null, 0); os.dup2(out, 1); os.dup2(out, 2)
child = subprocess.Popen(cmd)
os.write(w, str(child.pid).encode()); os.close(w)
code = child.wait()
os.write(out, f"EXIT {code} {time.strftime('%H:%M:%S')}\n".encode())
PY
