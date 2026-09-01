#!/usr/bin/env python3
"""dev/p7probe.py -- protocol-level checks for the warm server.

Driven by dev/p7probe.sh, which owns the server's lifecycle (a probe-private
server name, and a trap that stops it however the run ends).  This half speaks
the line protocol directly, through the shipped client's own helpers, so what
is tested is the code that ships rather than a second implementation of it.

What it proves, in order:

  1. the handshake -- protocol number, version, and a component id that agrees
     with the jar this checkout actually built;
  2. `query_open` -- an index gets built, and re-opening it is a stat sweep
     rather than a reparse;
  3. warm/cold parity -- a spread of verbs, byte-for-byte against the cold
     `isabelle query`, exit statuses included;
  4. invalidation -- an edited theory changes the answer on the very next
     request, and a new file appears without a manual refresh;
  5. refusals -- over the size cap the reply is a protocol ERROR carrying a
     message, NOT an OK with an empty answer;
  6. the namespace -- a request that binds the broad table unconditionally
     does not leave it bound for the next project;
  7. errors -- a bad index id, a missing argv, a malformed command;
  8. failability -- with $P7PROBE_FAILDEMO set, one expectation is perturbed
     and the run must go red.

Usage:  p7probe.py SERVER_NAME AFP_ENTRY ZF_CORPUS SCRATCH_DIR
"""

import os
import shutil
import subprocess
import sys

sys.path.insert(
    0,
    os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "query_base",
        "lib",
        "scripts",
    ),
)

import query_client as Q  # noqa: E402


CHECKS = 0
FAILS = 0
FAILDEMO = os.environ.get("P7PROBE_FAILDEMO") == "1"


def note(what, detail=""):
    global CHECKS
    CHECKS += 1
    print("  ok    %s%s" % (what, "  [%s]" % detail if detail else ""))


def bad(what, detail):
    global CHECKS, FAILS
    CHECKS += 1
    FAILS += 1
    print("  FAIL  %s  [%s]" % (what, detail))


def check(cond, what, detail=""):
    if cond:
        note(what, detail)
    else:
        bad(what, detail or "condition false")


def cold(root, argv):
    """The reference answer for every parity check: the same engine, in a
    process that starts and exits."""
    proc = subprocess.run(
        ["isabelle", "query", "-R", root] + argv,
        capture_output=True,
        text=True,
    )
    return proc.returncode, proc.stdout, proc.stderr


def main(argv):
    if len(argv) != 4:
        sys.stderr.write(__doc__.rsplit("Usage:", 1)[-1].strip() + "\n")
        return 2
    name, afp, zf, scratch = argv

    cached = Q.read_cache()
    isabelle = Q.find_isabelle(cached)
    conn = Q.connect(isabelle, cached, name, False, 600.0, False)

    # ------------------------------------------------------------------
    print("1. handshake")
    head, body = conn.command("query_version", {})
    check(head == "OK", "query_version answers OK", head)
    check(body.get("protocol") == Q.PROTOCOL,
          "protocol number matches the client's", str(body.get("protocol")))
    check(str(body.get("version", "")).endswith("-scala"),
          "version is the fork's", str(body.get("version")))
    stamp = Q.jar_stamp()
    check(body.get("component_id") == stamp,
          "component id is this checkout's jar", str(body.get("component_id")))
    check(os.path.isfile(str(body.get("jar", ""))),
          "the server names a jar that exists", str(body.get("jar")))

    # ------------------------------------------------------------------
    print()
    print("2. query_open, and what a re-open costs")
    # From a KNOWN state: this probe may run twice against one server (the
    # failability demo is a second run), and "the first open parses every
    # theory" is only a statement about a root nobody has opened yet.
    conn.command("query_close", {"root": afp})
    head, first = conn.command("query_open", {"root": afp})
    check(head == "OK", "query_open answers OK", head)
    check(first.get("theories", 0) > 0 and first.get("entries", 0) > 0,
          "the index has theories and entries",
          "%s theories, %s entries" % (first.get("theories"), first.get("entries")))
    check(first.get("reparsed", -1) == first.get("theories"),
          "the first open parses every theory", str(first.get("reparsed")))
    head, again = conn.command("query_open", {"root": afp})
    check(again.get("index_id") == first.get("index_id"),
          "re-opening the same root reuses the index", str(again.get("index_id"))[:8])
    check(again.get("reparsed") == 0,
          "and reparses nothing", "recheck %s ms over %s files"
          % (again.get("check_ms"), again.get("files_checked")))

    # ------------------------------------------------------------------
    print()
    print("3. warm/cold parity -- the served answer IS the typed answer")
    cases = [
        ["summary"],
        ["theory", "--names"],
        ["find", "."],
        ["largest", "-n", "3"],
        ["outline"],
        ["defs"],
        ["lines"],
        ["sorry"],
        ["callers", "mono"],
        ["callees", "mono"],
        ["deps"],
        ["refs"],
        ["unused"],
        ["methods"],
        ["shape", "summary"],
        ["shape", "widest"],
        ["instances", "monoid"],
        ["codeqs", "rev"],
        ["show", "NoSuchEntryAnywhere"],
        ["at", "NoSuchTheory:1"],
    ]
    mismatch = []
    for case in cases:
        rc_c, out_c, err_c = cold(afp, case)
        head, body = conn.command(
            "query_run", {"argv": ["-R", afp] + case, "cwd": scratch, "env_root": ""}
        )
        if head != "OK":
            mismatch.append("%s: reply %s" % (" ".join(case), head))
            continue
        if body.get("output") != out_c:
            mismatch.append("%s: stdout differs" % " ".join(case))
        elif body.get("exit") != rc_c:
            mismatch.append("%s: exit %s vs %s" % (" ".join(case), body.get("exit"), rc_c))
        elif bool(body.get("error")) != bool(err_c):
            mismatch.append("%s: stderr presence differs" % " ".join(case))
    check(not mismatch, "%d invocations agree with the cold tool" % len(cases),
          "; ".join(mismatch[:3]) if mismatch else "stdout, stderr presence, exit")

    # An exit status is DATA, not a protocol failure.  `codeqs` on a name that
    # is not a constant is the CLI's exit 1 (P6b's contract), and it must
    # arrive as OK carrying that 1 -- a wrapper that saw ERROR could not tell
    # a refused subject from a crashed server.
    head, body = conn.command(
        "query_run", {"argv": ["-R", afp, "codeqs", "no_such_constant_at_all"],
                      "cwd": scratch, "env_root": ""})
    check(head == "OK" and body.get("exit") == 1 and body.get("error"),
          "an unresolved subject is OK with exit 1 and a diagnostic",
          "%s exit=%s" % (head, body.get("exit")))

    head, body = conn.command(
        "query_run", {"argv": ["-R", afp, "no-such-command"], "cwd": scratch,
                      "env_root": ""})
    check(head == "OK" and body.get("exit") == 2 and body.get("error"),
          "a usage error is OK with exit 2 and a diagnostic",
          "%s exit=%s" % (head, body.get("exit")))

    # ------------------------------------------------------------------
    print()
    print("4. invalidation -- an edit is visible on the next request")
    work = os.path.join(scratch, "invalidate")
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(afp, work)
    rc_c, out_c, _ = cold(work, ["find", "p7probe_marker", "--names"])
    head, body = conn.command(
        "query_run", {"argv": ["-R", work, "find", "p7probe_marker", "--names"],
                      "cwd": scratch, "env_root": ""})
    # The absence to test for is a MATCH LINE, not the string: "No entries
    # matching 'p7probe_marker'." would name the marker itself.  Since P9 S1
    # [count-mode-zero] a `--names` mode with nothing to list prints NOTHING at
    # all -- an empty list is the right answer for a pipeline, and the sentence
    # would be read as a name -- so the corpus-before state is empty stdout,
    # exit 0, warm and cold alike.  (Before that it was the sentence, which is
    # what this check used to look for.)
    check(head == "OK" and body.get("output") == out_c and body.get("exit") == rc_c
          and out_c.strip() == "",
          "the marker is absent to begin with, warm and cold alike",
          "exit %s, %r" % (body.get("exit"), out_c[:40]))

    victim = None
    for entry in sorted(os.listdir(work)):
        if entry.endswith(".thy"):
            victim = os.path.join(work, entry)
            break
    if victim is None:
        bad("an edited theory changes the answer", "no .thy in the copy")
    else:
        text = open(victim, encoding="utf-8").read()
        cut = text.rindex("\nend")
        edited = (text[:cut] +
                  '\n\nlemma p7probe_marker: "True" by simp\n' + text[cut:])
        # A same-millisecond rewrite is exactly what the size half of the key
        # is for, so do not sleep first.
        open(victim, "w", encoding="utf-8").write(edited)
        head, body = conn.command(
            "query_run", {"argv": ["-R", work, "find", "p7probe_marker", "--names"],
                          "cwd": scratch, "env_root": ""})
        check(head == "OK" and "p7probe_marker" in body.get("output", ""),
              "an edited theory changes the answer at once",
              "exit %s, refresh %s ms" % (body.get("exit"), body.get("refresh_ms")))
        rc_c, out_c, _ = cold(work, ["find", "p7probe_marker", "--names"])
        check(body.get("output") == out_c and body.get("exit") == rc_c,
              "and the changed answer still equals the cold one")

        added = os.path.join(work, "P7probe_New.thy")
        open(added, "w", encoding="utf-8").write(
            "theory P7probe_New imports Main begin\n"
            'lemma p7probe_added: "True" by simp\n'
            "end\n")
        head, body = conn.command(
            "query_run", {"argv": ["-R", work, "find", "p7probe_added", "--names"],
                          "cwd": scratch, "env_root": ""})
        # The file is an orphan -- no ROOT names it -- so discovery must NOT
        # load it, exactly as a cold run would not.  What is under test is
        # that the two agree, not that a new file is picked up regardless.
        rc_c, out_c, _ = cold(work, ["find", "p7probe_added", "--names"])
        check(body.get("output") == out_c and body.get("exit") == rc_c,
              "a file added on disk lands warm exactly as it lands cold",
              "exit %s" % body.get("exit"))

        os.remove(added)
        os.remove(victim)
        head, body = conn.command(
            "query_run", {"argv": ["-R", work, "find", "p7probe_marker", "--names"],
                          "cwd": scratch, "env_root": ""})
        rc_c, out_c, _ = cold(work, ["find", "p7probe_marker", "--names"])
        check(body.get("output") == out_c and body.get("exit") == rc_c,
              "and a DELETED theory drops out of the warm index too",
              "exit %s" % body.get("exit"))
    conn.command("query_close", {"root": work})
    shutil.rmtree(work, ignore_errors=True)

    # ------------------------------------------------------------------
    print()
    print("5. refusals arrive as protocol errors")
    head, body = conn.command(
        "query_run", {"argv": ["-R", afp, "summary"], "cwd": scratch,
                      "env_root": "", "limit": 1})
    check(head == "ERROR", "over the cap the reply is ERROR, not OK", head)
    check("too large for a resident index" in str(body.get("message", "")),
          "and the message says what to do about it",
          str(body.get("message", ""))[:70])
    check("output" not in body,
          "and carries no empty answer to be mistaken for a result")

    empty = os.path.join(scratch, "empty-root")
    os.makedirs(empty, exist_ok=True)
    head, body = conn.command("query_open", {"root": empty})
    check(head == "ERROR", "an empty root is ERROR too", head)

    head, body = conn.command("query_open", {"root": os.path.join(scratch, "nope")})
    check(head == "ERROR", "and so is a root that does not exist", head)

    # ------------------------------------------------------------------
    print()
    print("6. the namespace does not leak between requests")
    # `census` binds the broad HOL union unconditionally.  A ZF project must
    # step DOWN to the Pure floor, and would silently not if the previous
    # request's binding survived -- `induct` is a HOL method, so under the
    # broad table it stops being a citation.
    rc_zf, zf_cold, _ = cold(zf, ["callers", "induct"])
    conn.command("query_run", {"argv": ["-R", zf, "shape", "census"],
                               "cwd": scratch, "env_root": ""})
    head, body = conn.command(
        "query_run", {"argv": ["-R", zf, "callers", "induct"], "cwd": scratch,
                      "env_root": ""})
    check(head == "OK" and body.get("output") == zf_cold and body.get("exit") == rc_zf,
          "a ZF `callers` after a corpus-wide shape run equals the cold answer",
          "exit %s vs %s" % (body.get("exit"), rc_zf))

    # And the other order: the HOL project must not inherit ZF's Pure floor.
    rc_h, hol_cold, _ = cold(afp, ["callers", "mono"])
    head, body = conn.command(
        "query_run", {"argv": ["-R", afp, "callers", "mono"], "cwd": scratch,
                      "env_root": ""})
    check(head == "OK" and body.get("output") == hol_cold,
          "and the HOL project right after it does not inherit the Pure floor")

    # ------------------------------------------------------------------
    print()
    print("7. bad requests are refused, not guessed at")
    head, body = conn.command("query_run", {"argv": ["summary"],
                                            "index_id": "not-a-real-id"})
    check(head == "ERROR" and "no such index" in str(body.get("message", "")),
          "an unknown index id is an error", head)

    head, body = conn.command("query_run", {"cwd": scratch})
    check(head == "ERROR", "a request with no argv is an error", head)

    head, body = conn.command("query_close", {"index_id": "not-a-real-id"})
    check(head == "ERROR", "closing an unknown index is an error", head)

    head, body = conn.command("query_nonesuch", {})
    check(head == "ERROR", "an unknown command is an error", head)
    # The connection must survive all four: an error is an answer, not a
    # reason to drop the client.
    head, body = conn.command("query_version", {})
    check(head == "OK", "and the connection survives them all", head)

    # ------------------------------------------------------------------
    print()
    print("8. close")
    head, body = conn.command("query_version", {"open": True})
    open_before = len(body.get("open", []))
    head, body = conn.command("query_close", {"root": afp})
    check(head == "OK" and body.get("closed") == 1, "query_close drops one index",
          str(body.get("closed")))
    head, body = conn.command("query_version", {"open": True})
    check(len(body.get("open", [])) == open_before - 1,
          "and the index is gone from the report",
          "%d -> %d" % (open_before, len(body.get("open", []))))
    head, body = conn.command("query_close", {})
    check(head == "OK", "closing everything answers OK",
          "%s dropped" % body.get("closed"))

    if FAILDEMO:
        check(False, "FAILDEMO: a deliberately impossible expectation", "by design")

    conn.close()
    print()
    print("%d protocol checks: %d failing" % (CHECKS, FAILS))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
