#!/usr/bin/env bash
# Sourced by benchmark/matrix harnesses. Caller installs EXIT/INT/TERM traps
# before owned_server_start and calls owned_server_stop from its EXIT trap.
# USER_HOME must be a supplied private home with the verified component;
# client is the matching copied component's query_client.py, not a live build.
OWNED_SERVER_PID=""
OWNED_SERVER_OUT=""
owned_server_start() {
  local client="$1" out="$2" timeout="${3:-60}" parent_pid="$BASHPID"
  [[ "$timeout" =~ ^[1-9][0-9]*$ ]] || return 2
  [[ "${USER_HOME:-}" = /* && -d "$USER_HOME" && "$USER_HOME" != "$HOME" ]] || {
    echo "owned-server: supply an absolute private USER_HOME" >&2; return 2;
  }
  local actual_home
  actual_home=$(isabelle getenv -b ISABELLE_HOME_USER) || return
  case "$actual_home" in "$USER_HOME"/*) ;; *)
    echo "owned-server: Isabelle home escaped private USER_HOME" >&2; return 2;; esac
  umask 077
  mkdir -p "$out" || return
  OWNED_SERVER_OUT="$out"
  local name
  name=$(python3 -c 'import uuid; print(uuid.uuid4().hex)') || return
  export ISABELLE_QUERY_CLIENT_SERVER="owned-$name"
  export ISABELLE_QUERY_CLIENT_CACHE="$out/client-cache.json"
  # Startup stdout contains the authentication greeting: keep it private.
  python3 - "$client" "$ISABELLE_QUERY_CLIENT_SERVER" "$out" "$timeout" "$parent_pid" <<'PY_OWNER' >"$out/owner.log" 2>&1 &
import contextlib, importlib.util, json, os, signal, subprocess, sys, threading, time
client, name, out, timeout, parent = sys.argv[1:]
parent = int(parent)
os.umask(0o077)
launching = False
stop_requested = False
def terminate(*_):
    global stop_requested
    if launching:
        stop_requested = True
    else:
        raise SystemExit(0)
signal.signal(signal.SIGTERM, terminate)
signal.signal(signal.SIGINT, terminate)
proc = None

def publish(file, data):
    path = os.path.join(out, file)
    with open(path + ".tmp", "w") as f:
        json.dump(data, f)
    os.replace(path + ".tmp", path)

# Record kernel identity for read-only bounded completion checks. No external
# controller ever sends a signal to the supervisor's potentially recycled PID.
with open("/proc/self/stat") as f:
    start_time = f.read().rsplit(")", 1)[1].split()[19]
publish("owner-identity.json", {"pid": os.getpid(), "start_time": start_time})

@contextlib.contextmanager
def deferred_stop():
    previous = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGTERM, signal.SIGINT})
    try:
        yield
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous)


def watch_owner():
    while os.getppid() == parent and not os.path.exists(out + "/stop-request"):
        time.sleep(.05)
    # This is our OWN live process. Interrupt blocking getenv/probe startup as
    # well as the idle loop; finally closes/kills only our launcher group.
    os.kill(os.getpid(), signal.SIGTERM)


try:
    # Watcher inherits the blocked mask; only the main thread handles stop.
    with deferred_stop():
        threading.Thread(target=watch_owner, daemon=True).start()
    spec = importlib.util.spec_from_file_location("owned_client", client)
    Q = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(Q)
    with open(out + "/server.log", "wb") as log:
        # Defer the exception until Popen assigns the handle. Keep signals
        # unblocked here so the child does not inherit a blocked TERM mask.
        launching = True
        try:
            proc = subprocess.Popen(["isabelle", "query_server", "-n", name],
                                    stdin=subprocess.PIPE, stdout=log, stderr=log,
                                    start_new_session=True)
        finally:
            launching = False
        if stop_requested:
            raise SystemExit(0)
        deadline = time.monotonic() + int(timeout)
        ready = False
        while os.getppid() == parent:
            # waitid WNOWAIT observes exit without reaping our group leader.
            exited = os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            if exited is not None:
                raise RuntimeError("owned launcher exited")
            if not ready:
                cached = Q.read_cache()
                endpoint = Q.registry_lookup(Q.find_isabelle(cached), cached, name)
                if endpoint:
                    conn = None
                    try:
                        conn = Q.probe(name, endpoint, min(deadline, time.monotonic() + 1))
                        if not Q.compatible(conn, Q.jar_stamp()):
                            raise RuntimeError("owned server component is incompatible")
                        if not conn.version.get("retain_indexes", False):
                            raise RuntimeError("warm harness requires index retention enabled")
                        publish("server-ready.json", {k: conn.version.get(k) for k in
                                ("host_kind", "retain_indexes", "cache_limit_bytes", "component_id")})
                        ready = True
                    except (OSError, Q.Fallback):
                        if time.monotonic() >= deadline:
                            raise RuntimeError("owned server readiness timed out") from None
                    finally:
                        if conn is not None:
                            conn.close()
                if not ready and time.monotonic() >= deadline:
                    raise RuntimeError("owned server readiness timed out")
            time.sleep(.1)
except Exception:
    publish("server-failed.json", {"error": "owned server failed; inspect private owner.log"})
    raise
finally:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    if proc is not None:
        # EOF is the normal stop. Keep the leader unreaped until the exact
        # owned group has been signalled, so its PID cannot be recycled.
        proc.stdin.close()
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            if os.waitid(os.P_PID, proc.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT):
                break
            time.sleep(.05)
        for sig in (signal.SIGTERM, signal.SIGKILL):
            try:
                os.killpg(proc.pid, sig)
            except ProcessLookupError:
                pass
            if sig == signal.SIGTERM:
                time.sleep(.2)
        proc.wait(timeout=1)
    publish("owner-finished.json", {})
PY_OWNER
  OWNED_SERVER_PID=$!
  python3 - "$out" "$timeout" <<'PY_READY'
import os, sys, time
out, timeout = sys.argv[1:]
deadline = time.monotonic() + int(timeout) + 3
while not os.path.isfile(out + "/server-ready.json"):
    if (os.path.isfile(out + "/server-failed.json") or
            os.path.isfile(out + "/owner-finished.json") or time.monotonic() >= deadline):
        raise SystemExit("owned-server: readiness failed; inspect private owner.log")
    time.sleep(.1)
PY_READY
}

owned_server_stop() {
  [ -n "$OWNED_SERVER_PID" ] || return 0
  local rc=0
  # A file request cannot target a recycled PID. The owner watchdog handles it
  # even while the main thread is blocked in initialization.
  : >"$OWNED_SERVER_OUT/stop-request"
  python3 - "$OWNED_SERVER_OUT" "$OWNED_SERVER_PID" <<'PY_STOP'
import json, os, sys, time
out, pid = sys.argv[1:]
deadline = time.monotonic() + 6
while True:
    try:
        with open("/proc/" + pid + "/stat") as f:
            fields = f.read().rsplit(")", 1)[1].split()
    except FileNotFoundError:
        break
    if fields[0] in ("Z", "X"):
        break
    try:
        with open(out + "/owner-identity.json") as f:
            identity = json.load(f)
        if str(identity["pid"]) != pid or fields[19] != identity["start_time"]:
            break  # original owner exited and its PID was recycled
    except FileNotFoundError:
        pass  # owner may have died before publishing its identity
    if time.monotonic() >= deadline:
        raise SystemExit("owned-server: bounded owner stop failed; inspect private artifacts")
    time.sleep(.05)
PY_STOP
  rc=$?
  [ "$rc" -eq 0 ] || return "$rc"
  # The original process has exited, so this job-status reap cannot block.
  wait "$OWNED_SERVER_PID" || rc=$?
  OWNED_SERVER_PID=""
  return "$rc"
}
