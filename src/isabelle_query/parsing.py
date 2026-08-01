"""Theory parsing — .thy source into the :class:`~isabelle_query.model.Entry`
database.

The second layer of the module DAG (above ``model``, below everything else).
Everything here is a *pure function of source text*: the declaration grammar
(``DECL_RE`` and the name-extraction helpers), the custom outer-syntax keyword
scanner, span attribution (``compute_spans`` / ``_attach_preambles`` /
``_attach_roadmaps``), the per-theory parse (``_parse_one`` / ``_parse_plain``),
and the ROOT-walking enumeration (``_sections_from_dir``).  No dependency on
the call graph, rendering, or the CLI, so the whole tree can be parsed without
touching any of them.

Two module globals live here because they belong to the parse:

* ``_CUSTOM_COMMANDS`` — the active root's union of per-header ``keywords``
  tables (Isabelle's session-wide ``Keywords.++``), cleared and rebuilt by
  ``cli.load_index`` before each scan.  It is mutated in place (``.clear()`` /
  ``.update()``), never rebound, so the ``cli`` facade's re-export stays the
  same object.

Note: *which* root to parse (``active_t_dir`` / ``load_index`` / the
``_ROOT_OVERRIDE`` set by ``--root``) is a config concern and stays in ``cli``
— the test suite rebinds ``cli._ROOT_OVERRIDE`` directly, so that binding must
live where the tests reach it.
"""

from __future__ import annotations

import re
from bisect import bisect_right
from collections.abc import Callable, Iterable
from pathlib import Path

from isabelle_query.common import (
    discover_roots,
    parse_root_sessions,
    session_theories,
)
from isabelle_query.model import Entry, TheorySection

# `(?=\s|$)` (a token boundary), not a consumed `\s`, so a keyword standing
# ALONE on its line — the "name on a following line" form — still matches.
# It stays a whole-word test (`definitions`/`inductively` do not match), and
# being zero-width it leaves the `line[len(keyword):]` slicing untouched.
DECL_RE = re.compile(
    r"^(definition|abbreviation|function|fun|primrec|inductive_set|inductive|lemma|corollary|theorem|axiomatization|datatype|type_synonym|record)(?=\s|$)"
)

TAG_MAP = {
    "definition": "DEF", "abbreviation": "ABBREV",
    "function": "FUN", "fun": "FUN", "primrec": "FUN",
    "inductive_set": "INDSET", "inductive": "IND",
    "lemma": "LEMMA", "corollary": "LEMMA",
    "theorem": "THEOREM",
    "axiomatization": "AXIOM",
    "datatype": "DATATYPE", "type_synonym": "TYPE", "record": "RECORD",
}


# --- Custom outer-syntax commands (faithful keyword-table scan) ------------
# An AFP entry may define its own theory commands (AOT's `AOT_theorem`,
# `AOT_define`, ...) through Isabelle's command framework.  The one fact the
# regex parser cannot otherwise know — that `AOT_theorem` is a theorem-like
# command — is declared as PLAIN TEXT in a theory header, and that declaration
# *is* Isabelle's keyword table (Pure/Thy/thy_header.ML parses exactly the
# `keywords "name" :: kind` clause we scan).  So recognising these commands is
# faithful, not a `<Prefix>_theorem` name-guess.  Each declared command's
# `kind` maps to one of the existing tag families below; we then route the
# command through the same name/branch logic as the matching built-in.
#
# kind -> family follows Pure/Isar/keyword.scala:
#   theory_goal = {thy_goal, thy_goal_stmt, thy_goal_defn}  (proof-bearing)
#   theory_defn = {thy_defn, thy_goal_defn}                 (introduces a def)
#   thy_decl / thy_decl_block / thy_stmt                    (declarations)
# Proof (prf_*), diagnostic (diag), document, load and quasi_command kinds are
# intentionally absent: they introduce no citable fact, so they must NOT create
# an entry.  `thy_goal_defn` both defines and proves; for a *custom* command of
# that kind we tag it pragmatically as a goal so its name and proof are picked
# up.  (The built-in `function` is the common `thy_goal_defn`, but it is handled
# by DECL_RE as a `FUN` definition — like `fun` — so its constant lands in
# `defs`; the trailing `by`/`termination` proof falls inside the def body span.)
_KIND_FAMILY = {
    "thy_goal": "THEOREM",
    "thy_goal_stmt": "THEOREM",
    "thy_goal_defn": "THEOREM",
    "thy_defn": "DEF",
    "thy_decl": "DEF",
    "thy_decl_block": "DEF",
    "thy_stmt": "DEF",
}

# The union of every scanned header's command table for the active root,
# populated by load_index()'s header pre-scan (mirroring Isabelle's
# session-wide `Keywords.++`).  Empty by default, so a bare extract_entries
# call behaves exactly as before — and every body-scan custom-command check
# is guarded by `if table`, costing nothing when no custom commands exist.
_CUSTOM_COMMANDS: dict[str, str] = {}


def _route_for(keyword: str, tag: str) -> str:
    """Which extract_entries branch handles a command with this tag.

    Derived from the tag (not a second keyword table) so built-in and custom
    commands route uniformly: a custom `thy_goal` command (tag THEOREM) takes
    the same `goal` branch as `theorem`, a custom `thy_decl`/`thy_defn` (tag
    DEF) the same `def` branch as `definition`."""
    if keyword == "axiomatization":
        return "axiom"
    if tag in ("DATATYPE", "TYPE", "RECORD"):
        return "typedecl"
    if tag in ("LEMMA", "THEOREM"):
        return "goal"
    return "def"  # DEF, ABBREV, FUN, INDSET, IND, and custom thy_decl/thy_defn

PROOF_RE = re.compile(
    r"^\s*(proof\b|by\b|sorry\b|oops\b|using\b"
    r"|unfolding\b|apply\b|\.\.\s*$)"
)
BLANK_RE = re.compile(r"^\s*$")
TOPLEVEL_RE = re.compile(r"^[a-z]")
SECTION_RE = re.compile(r"^(chapter|section|subsection|subsubsection)\s+\\<open>(.*)")
TEXT_OPEN_RE = re.compile(r"^\s*(text|text_raw)\s*\\<open>")
COMMENT_LINE_RE = re.compile(r"\\<comment>\s*\\<open>(.*)$")
LATEX_LINE_RE = re.compile(
    r"\\(begin|end|caption|node|draw|newlength|newcommand|settowidth|settoheight|scalebox|label)\b"
)
# Isabelle fact/definition names that contain non-identifier characters
# (-, :, [, ], digits after a colon, ...) must be double-quoted at their
# declaration site, e.g. `theorem "beta-C-cor:3":`.  Capture the quoted
# spelling verbatim so `show`/`callers` can find the entry by the name the
# source actually uses.
QUOTED_NAME_RE = re.compile(r'^"([^"]+)"')
# A bare name may interleave ASCII identifier characters with Isabelle
# symbol tokens written `\<...>` (e.g. \<psi>, \<alpha>ah, \<tau>rtrancl3p)
# and subscript controls (\<^sub>1).  Treating `\<...>` runs as name
# characters captures the many AFP entries whose names are Greek letters or
# decorated identifiers, which a plain `\w[\w']*` pattern misses.
SYM_NAME_RE = re.compile(r"((?:\\<\^?\w+>|\w)(?:\\<\^?\w+>|[\w'])*)")
# Isabelle structural control symbols are not fact names: cartouche
# delimiters (\<open>/\<close>) and the comment marker (\<comment>) can sit
# where a name is expected (a cartouche statement, or a `\<comment> \<open>
# ...\<close>` annotation), and must not be captured as the name.
RESERVED_NAME_PREFIXES = ("\\<open>", "\\<close>", "\\<comment>", "\\<^cancel>")
# Outer-syntax keywords that are not fact names.  When the name slot holds one
# of these *bare* — `lemma assumes ...`, `lemma fixes ...`, `... (eqvt) by ...`,
# `lemma shows NAME: ...` — the construct is anonymous (or its true name
# follows), and the keyword must not be captured as the name.  Only the BARE
# form is rejected: a *quoted* keyword (`fun "for"`, `lemma "if":`,
# `definition "and"`) is a legitimate, deliberately-quoted name and is parsed
# by the quoted branch of _name_from before this guard is reached.
_RESERVED_NAME_WORDS = frozenset({
    # Isar statement elements following an (anonymous) lemma/theorem
    "assumes", "shows", "fixes", "obtains", "defines", "notes", "constrains",
    # proof-script keywords
    "by", "using", "unfolding", "apply", "proof", "qed", "done", "oops",
    "sorry",
    # structural keywords that can land in a misparsed name slot
    "where", "for", "and", "if", "then", "else", "next", "case",
})
# A quoted spelling is a *name* only when it forms a label: the closing quote
# is followed, after optional [attributes], by ':'.  Otherwise the quotes hold
# the statement of an anonymous lemma (`lemma "P"`), not a name.
LABEL_AFTER_RE = re.compile(r"\s*(?:\[[^\]]*\]\s*)*:")

# Named conjuncts of a multi-`shows` lemma: `shows NAME:` / `and NAME:`
# in the *shows* region.  Gated by the SHOWS_*_RE so the `assumes ... and
# X:` region — whose `and`-bound names are hypotheses, not citable facts —
# is excluded (shows always follows assumes in Isabelle's lemma grammar).
SHOWS_AT_START_RE = re.compile(r"shows\b")     # applied to a stripped line
SHOWS_ANYWHERE_RE = re.compile(r"\bshows\b")   # applied to the decl-line rest
CONJUNCT_RE = re.compile(r"(?:shows|and)\s+(\w[\w']*)\s*:")


# A fact name that contains a character outside the identifier/symbol set —
# a hyphen, colon, bracket, etc. (`beta-C-cor:3`, `num:1`, `denote=:4[3]`) —
# cannot be written bare in a reference; Isabelle requires it double-quoted.
# Such names are also frequently substrings of one another (`num:1` of
# `eq-num:1`, `safe-ext` of `safe-ext[3]`), so a `[\w']`-boundary search
# spuriously matches the short one inside the long one.
_SPECIAL_NAME_RE = re.compile(r"[^\w'\\<>^]")


def _isa_word_pattern(name: str) -> str:
    r"""Return a regex matching `name` as a complete Isabelle name reference.

    Three cases, each matching exactly where a real citation can occur:

    * **Special-character names** (hyphen/colon/bracket — must be quoted in
      source): match only when flanked by the double-quotes, so `num:1` is
      not found inside `"eq-num:1"`.
    * **Symbolic names** written with `\<...>` tokens: a name that *ends* in
      `>` must not be glued to a following `\<...>` symbol, and one that
      *starts* with `\<` must not follow a preceding `>` — otherwise `\<gamma>`
      would match inside `\<gamma>\<^sub>1`.  (A bare ASCII run abutting a
      symbol, e.g. `foo` in `foo\<gamma>`, is still a match — it does not end
      in `>`.)
    * **Plain identifiers**: a prime-aware word boundary — `\b` is wrong
      because Isabelle allows `'` inside identifiers (`foo'`).
    """
    if _SPECIAL_NAME_RE.search(name):
        return r'(?<=")' + re.escape(name) + r'(?=")'
    left = r"(?<![\w'])" + (r"(?<!>)" if name.startswith("\\<") else "")
    right = (r"(?!\\<)" if name.endswith(">") else "") + r"(?![\w'])"
    return left + re.escape(name) + right


def _balanced_end(s: str, open_tok: str, close_tok: str, *,
                  start: int = 0, quote_aware: bool = False) -> int:
    r"""Index in ``s`` just past the ``close_tok`` matching the ``open_tok`` that
    ``s[start:]`` opens with; ``-1`` if the delimiter never closes on this text.

    The package's single balanced-delimiter scanner.  A depth counter is a
    one-symbol pushdown automaton — the least machinery a *non-regular* nesting
    construct needs, and precisely what a regular expression cannot supply — so
    every place that must respect Isar nesting routes through here rather than
    re-rolling the loop.  Parameterising the token pair lets one scanner serve
    both the character paren ``(``/``)`` and the multi-byte symbol cartouche
    ``\<open>``/``\<close>`` (which nest alike); ``str.startswith`` — not ``==`` —
    is what makes a multi-character delimiter a token like any other.  With
    ``quote_aware`` a delimiter inside a ``"..."`` term is ignored, where a stray
    paren is data, not structure."""
    depth = 0
    i, n = start, len(s)
    in_quote = False
    while i < n:
        if quote_aware and s[i] == '"':
            in_quote = not in_quote
            i += 1
        elif in_quote:
            i += 1
        elif s.startswith(open_tok, i):
            depth += 1
            i += len(open_tok)
        elif s.startswith(close_tok, i):
            depth -= 1
            i += len(close_tok)
            if depth == 0:
                return i
        else:
            i += 1
    return -1


def _balanced_paren_end(s: str) -> int:
    """Index just past the ')' matching a leading '(' (s must start with
    '('), accounting for nesting; -1 if unbalanced.  The intention-revealing
    name for the common case — a thin façade over :func:`_balanced_end`."""
    return _balanced_end(s, "(", ")")


def _balanced_cartouche_end(s: str) -> int:
    r"""Index just past the `\<close>` matching a leading `\<open>` (s must
    start with `\<open>`), accounting for nesting; -1 if unbalanced (e.g. a
    cartouche that runs past the end of this line).  The cartouche is the
    symbol-level analogue of a paren, so it is the same :func:`_balanced_end`
    scan with the `\<open>`/`\<close>` token pair."""
    return _balanced_end(s, "\\<open>", "\\<close>")


def _strip_decl_prefix(s: str, typevars: bool) -> str:
    r"""Drop the syntactic noise that can sit between a keyword and the name.

    A fact or type name never starts with '(', a type variable, or a margin
    comment, so this only removes:
      * command modifiers / locale specs — ``(in foo)``, ``(nonexhaustive)``,
        ``(overloaded)``, ``(discs_sels)``, ``(sequential)``, ...
      * a leading margin comment ``\<comment> \<open>...\<close>`` that annotates
        the declaration before its name;
      * for type declarations (``typevars=True``), leading type arguments,
        either bare (``'a``) or grouped (``('a, 'b)``).
    """
    while s:
        if s[0] == "(":
            j = _balanced_paren_end(s)
            if j < 0:
                break
            s = s[j:].lstrip()
            continue
        if s.startswith("\\<comment>"):
            s = s[len("\\<comment>"):].lstrip()
            if s.startswith("\\<open>"):
                k = _balanced_cartouche_end(s)
                if k < 0:
                    break               # comment runs past this line
                s = s[k:].lstrip()
            continue
        if typevars and s[0] == "'":
            m = re.match(r"'[\w']+\s+", s)
            if m:
                s = s[m.end():]
                continue
        break
    return s


def _name_from(s: str, require_label: bool) -> str:
    """Parse a name from `s` (already stripped of any decl prefix): a
    double-quoted spelling, else a symbol-aware identifier, else '?'.

    With ``require_label`` (fact commands), a quoted spelling counts only as a
    *label* — followed, after optional [attributes], by ':'.  Otherwise the
    quotes hold the statement of an anonymous lemma (`lemma "P"`), not a name.
    Type declarations pass ``require_label=False``: a quoted type name
    (`datatype 'a "term"`) is followed by '=' / where, not ':'.
    """
    mq = QUOTED_NAME_RE.match(s)
    if mq and (not require_label or LABEL_AFTER_RE.match(s, mq.end())):
        return mq.group(1)
    m = SYM_NAME_RE.match(s)
    if not m:
        return "?"
    name = m.group(1)
    if name.startswith(RESERVED_NAME_PREFIXES) or name in _RESERVED_NAME_WORDS:
        return "?"
    return name


def _parse_name(text_after_tag: str) -> str:
    return _name_from(_strip_decl_prefix(text_after_tag.strip(), typevars=False),
                      require_label=True)


def _parse_typedecl_name(text_after_tag: str) -> str:
    r"""Parse a type_synonym / datatype / record's name, skipping any
    leading modifier (\<open>(discs_sels)\<close>) and type-argument list
    (\<open>'a\<close> or \<open>('a, 'b)\<close>)."""
    return _name_from(_strip_decl_prefix(text_after_tag.strip(), typevars=True),
                      require_label=False)


# A definitional connective: an implicit-name definition/abbreviation is
# written as a quoted equation `"lhs ... <connective> rhs"`.  Its presence is
# the signal that the quoted body is an equation whose LHS head is the name.
_DEF_CONNECTIVE_RE = re.compile(r"\\<equiv>|\\<rightleftharpoons>|==|=")


def _lhs_head_name(text_after_tag: str) -> str:
    r"""Head name of an implicit-name definition/abbreviation written as a
    quoted equation: `abbreviation "language_ltlc \<phi> \<equiv> ..."` ->
    ``language_ltlc``.  The name is the first identifier of the LHS — the
    constant being defined.

    Returns '?' unless the leading token (after any modifier/locale prefix) is
    a quoted body that actually contains a definitional connective, so a quoted
    *statement* (an anonymous `lemma "P"`) is never mistaken for a definition.
    Only the prefix-application case is handled; infix/mixfix definitions,
    whose operator sits between operands, are out of scope and stay '?'."""
    s = _strip_decl_prefix(text_after_tag.strip(), typevars=False)
    mq = QUOTED_NAME_RE.match(s)
    if not mq or not _DEF_CONNECTIVE_RE.search(mq.group(1)):
        return "?"
    return _name_from(mq.group(1).strip(), require_label=False)


def _parse_def_name(text_after_tag: str) -> str:
    """Name of a definition/abbreviation: a leading label/identifier as usual,
    else the LHS head of an implicit-name quoted equation."""
    name = _parse_name(text_after_tag)
    return name if name != "?" else _lhs_head_name(text_after_tag)


# The column-0 leading token of a line — the candidate command name for a
# custom-command match.  Anchored like DECL_RE: an indented line (proof body)
# has no match, so only top-level commands are considered.
_LEAD_TOKEN_RE = re.compile(r"^(\S+)")
# The header `keywords ... ` clause and its terminators (`abbrevs` / `begin`).
# Isabelle's header grammar (thy_header.ML:168) is
#   theory NAME imports ... [keywords <decls>] [abbrevs ...] begin
# so the keyword block runs from the `keywords` token to `abbrevs`/`begin`.
_HEADER_KEYWORDS_RE = re.compile(r"^\s*keywords\b")
_HEADER_BEGIN_RE = re.compile(r"^\s*begin\b")
_HEADER_END_RE = re.compile(r"^\s*(?:abbrevs|begin)\b")
# Tokeniser for the keyword block: a double-quoted name, else a bare run.
_KW_TOK_RE = re.compile(r'"([^"]*)"|(\S+)')


def _kw_tokenize(block: str) -> list[tuple[str, str]]:
    """Split a keyword block into ('name', value) for each double-quoted
    spelling and ('op', text) for every other run.  Quoting matters: a
    command name is always quoted, while the kind, load command and `% tags`
    are bare or quoted-but-after-`::`, so quoting lets us take names only from
    before the `::` and never mistake a `% "proof"` tag value for a name."""
    toks: list[tuple[str, str]] = []
    for m in _KW_TOK_RE.finditer(block):
        if m.group(1) is not None:
            toks.append(("name", m.group(1)))
        else:
            toks.append(("op", m.group(2)))
    return toks


def _kind_of(tok: str) -> str:
    """The leading identifier of a kind token (`thy_goal` from `thy_goal`,
    or from a glued `::thy_goal`'s tail)."""
    m = re.match(r"[A-Za-z_]+", tok)
    return m.group(0) if m else ""


def _parse_keyword_block(block: str, table: dict[str, str]) -> None:
    r"""Parse a header keyword block into ``table`` {command_name: tag}.

    Grammar (Pure/Thy/thy_header.ML:154-164):
      keyword_decls = and_list1(keyword_decl)
      keyword_decl  = repeat1(quoted_name) , optional( "::" kind (load)? (% tag)* )
    The names in one `and`-group share the single optional kind that follows
    them; a group with no `::` is a *minor* keyword (syntax), not a command, so
    it introduces no entry.  Only kinds in :data:`_KIND_FAMILY` map to a tag.
    """
    # Split the token stream into `and`-separated groups (the decl separator).
    groups: list[list[tuple[str, str]]] = [[]]
    for flag, val in _kw_tokenize(block):
        if flag == "op" and val == "and":
            groups.append([])
        else:
            groups[-1].append((flag, val))
    for g in groups:
        names: list[str] = []
        kind = ""
        seen_colon = False
        for flag, val in g:
            if not seen_colon and flag == "op" and val.startswith("::"):
                seen_colon = True
                if val != "::":            # glued `::thy_goal`
                    kind = _kind_of(val[2:])
                continue
            if seen_colon:
                if not kind and flag == "op":   # the kind, just after `::`
                    kind = _kind_of(val)
                continue                        # ignore load command / % tags
            if flag == "name":
                names.append(val)
        tag = _KIND_FAMILY.get(kind)
        if tag:
            for nm in names:
                if nm:
                    table[nm] = tag


def scan_keywords(lines: list[str]) -> dict[str, str]:
    """Return {command_name: tag} for the custom commands a theory's *own*
    header declares.  Scans only the header (up to the theory's `begin`), so
    it is cheap and never touches the body."""
    table: dict[str, str] = {}
    start = None
    for idx, line in enumerate(lines):
        if _HEADER_KEYWORDS_RE.match(line):
            start = idx
            break
        if _HEADER_BEGIN_RE.match(line):
            return table  # header ended (no keywords clause)
    if start is None:
        return table
    block = [re.sub(r"^\s*keywords\b", "", lines[start], count=1)]
    for line in lines[start + 1:]:
        if _HEADER_END_RE.match(line):
            break
        block.append(line)
    _parse_keyword_block(" ".join(block), table)
    return table


def _match_decl(line: str, table: dict[str, str]
                ) -> tuple[str, str, str] | None:
    """Return (keyword, tag, route) if `line` begins a recognised top-level
    command, else None.  Built-in commands match :data:`DECL_RE`; a custom
    command matches when the column-0 leading token is a name in ``table``
    (the scanned keyword table).  When ``table`` is empty this collapses to a
    single DECL_RE test — the pre-scan-free fast path used by every unit test
    and by non-custom theories."""
    m = DECL_RE.match(line)
    if m:
        kw = m.group(1)
        tag = TAG_MAP[kw]
        return kw, tag, _route_for(kw, tag)
    if table:
        m2 = _LEAD_TOKEN_RE.match(line)
        if m2:
            tag = table.get(m2.group(1))
            if tag:
                kw = m2.group(1)
                return kw, tag, _route_for(kw, tag)
    return None


# A decl keyword may stand alone on its line with the name on a *following*
# line (~1,866 AFP entries):
#     inductive_set
#       myset :: "nat set"
#     definition
#       foo :: "nat" where "foo = 0"
# Bound the forward scan to a few lines so a truncated/malformed file cannot
# run on looking for a name that is not there.
_NAME_LOOKAHEAD_LINES = 3


def _lookahead_name(lines: list[str], start: int, table: dict[str, str],
                    parse_fn) -> str:
    r"""The name for a decl whose keyword stood alone: scan forward from the
    0-indexed line ``start``, skipping blank / ``\<comment>`` / ``text`` lines,
    and parse the name from the **first content line** with ``parse_fn``.

    Only the first content line is consulted: a continuation name always sits
    immediately after the keyword.  If that line is an anonymous quoted
    statement (``definition "lhs = ..."``) ``parse_fn`` rightly yields ``'?'``
    — the decl really is anonymous, and scanning on would invent a name from
    unrelated following prose.  A following *top-level command* likewise means
    no name here.  Does NOT consume lines — the caller's body scan still covers
    the peeked line, so the body buffer, ``decl_end_line`` and spans are
    exactly as before; only the name changes."""
    end = min(len(lines), start + _NAME_LOOKAHEAD_LINES)
    j = start
    while j < end:
        stripped = lines[j].strip()
        if not stripped or stripped.startswith("\\<comment>") \
                or TEXT_OPEN_RE.match(lines[j]):
            j += 1
            continue
        if _match_decl(lines[j], table):     # next command — no name here
            return "?"
        return parse_fn(stripped)            # first content line is the name
    return "?"


def extract_sections(lines: list[str]) -> list[tuple[str, str, int]]:
    out: list[tuple[str, str, int]] = []
    for i, line in enumerate(lines, 1):
        m = SECTION_RE.match(line)
        if not m:
            continue
        level = m.group(1)
        rest = m.group(2)
        close_idx = rest.find("\\<close>")
        title = rest[:close_idx] if close_idx >= 0 else rest
        out.append((level, title.strip(), i))
    return out


def _find_balanced_close(lines: list[str], start: int) -> int:
    """Given a 0-indexed start line that opens a `\\<open>` block, return the
    0-indexed line of the matching `\\<close>` (counts open/close balance).
    Returns start if no balance found (malformed).
    """
    depth = 0
    for i in range(start, len(lines)):
        depth += lines[i].count("\\<open>")
        depth -= lines[i].count("\\<close>")
        if depth <= 0 and i >= start:
            return i
    return start


def _line_mask(n: int, spans: Iterable[tuple[int, int]]) -> bytearray:
    r"""A 1-indexed byte mask over ``n`` lines: ``mask[i]`` is 1 iff line ``i``
    lies in some inclusive ``[lo, hi]`` span.  Length ``n + 2`` so a probe at
    line ``n`` (or a ``+1`` sentinel) stays in bounds; each span is clamped to
    ``[1, n]`` and marked C-side by slice assignment.  Shared by the parse-time
    ``text``-block skip and the per-line prose / noise masks of the call-graph
    and method scans.
    """
    mask = bytearray(n + 2)
    for lo, hi in spans:
        lo = max(1, lo)
        hi = min(hi, n)
        if lo <= hi:
            mask[lo:hi + 1] = b"\x01" * (hi - lo + 1)
    return mask


def _scan_balanced_blocks(lines: list[str],
                          opens: Callable[[str], bool]
                          ) -> list[tuple[int, int]]:
    r"""Return [(start, end)] (1-indexed inclusive) for each balanced
    ``\<open>...\<close>`` block whose first line satisfies ``opens``, skipping
    past each block found.  Shared by `extract_text_blocks` (text / text_raw
    cartouches) and `extract_comment_ranges` (``\<comment>`` bodies), which
    differ only in that opening-line predicate.
    """
    out: list[tuple[int, int]] = []
    i = 0
    while i < len(lines):
        if opens(lines[i]):
            end = _find_balanced_close(lines, i)
            out.append((i + 1, end + 1))
            i = end + 1
        else:
            i += 1
    return out


def extract_text_blocks(lines: list[str]) -> list[tuple[int, int]]:
    """Return [(start_line, end_line)] (1-indexed inclusive) for top-level
    `text \\<open>...\\<close>` and `text_raw` blocks.  Body is not stored —
    callers slice from sec.source() when needed.
    """
    return _scan_balanced_blocks(lines, lambda ln: bool(TEXT_OPEN_RE.match(ln)))


def extract_comment_ranges(lines: list[str]) -> list[tuple[int, int]]:
    """Return [(start_line, end_line)] (1-indexed inclusive) for every
    \\<comment> \\<open>...\\<close> annotation, including multi-line bodies.

    Tracks \\<open>/\\<close> balance starting from the line that contains
    \\<comment>.  A \\<comment> on a line without \\<open> yields a single-
    line range (covers tag-only annotations without explicit body).
    """
    return _scan_balanced_blocks(lines, lambda ln: "\\<comment>" in ln)


def extract_comment_lines(lines: list[str]) -> list[tuple[int, str]]:
    """Return [(line_no, content)] for in-proof `\\<comment> \\<open>...\\<close>`
    annotations.  `content` is the prose text inside the `\\<open>...\\<close>`
    on the comment's first line (truncated at the first `\\<close>` if present).
    """
    out = []
    for i, line in enumerate(lines, 1):
        m = COMMENT_LINE_RE.search(line)
        if not m:
            continue
        rest = m.group(1)
        close_idx = rest.find("\\<close>")
        content = rest[:close_idx] if close_idx >= 0 else rest
        out.append((i, content.strip()))
    return out


def extract_entries(lines: list[str],
                    custom: dict[str, str] | None = None) -> list[Entry]:
    entries: list[Entry] = []
    i = 0

    # Recognised custom commands: this theory's own header declarations, the
    # active root's scanned union (_CUSTOM_COMMANDS, set by load_index), and an
    # explicit `custom` override (tests).  Empty for a plain theory with no
    # `keywords` clause, in which case _match_decl is just a DECL_RE test.
    table: dict[str, str] = dict(_CUSTOM_COMMANDS)
    table.update(scan_keywords(lines))
    if custom:
        table.update(custom)

    # Prose inside a `text \<open>...\<close>` / `text_raw` cartouche is a single
    # token to Isabelle, never outer syntax.  A column-0 line *inside* such a
    # block that happens to begin with a command name — notably a one-letter
    # command such as Isabelle_C's `C` — is prose, not a declaration, so we
    # skip those lines and never mint a phantom entry (or phantom span
    # boundary) from them.  (1-indexed; in_text[i+1] guards source line i+1.)
    in_text = _line_mask(len(lines), extract_text_blocks(lines))

    while i < len(lines):
        line = lines[i]
        if in_text[i + 1]:
            i += 1
            continue
        md = _match_decl(line, table)
        if md is None:
            i += 1
            continue

        keyword, tag, route = md
        decl_line = i + 1  # 1-indexed source line

        # --- Simple one-concept declarations ---
        if route == "typedecl":
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            name = _parse_typedecl_name(rest)
            if name == "?" and not _strip_decl_prefix(rest, typevars=True):
                name = _lookahead_name(lines, i + 1, table,
                                       _parse_typedecl_name)
            e = Entry(tag, name, f"{tag} {rest}",
                      thy_line=decl_line, decl_end_line=decl_line)
            entries.append(e)
            i += 1
            continue

        if route == "axiom":
            entries.append(Entry("AXIOM", "axiomatization", "AXIOMATIZATION",
                                 thy_line=decl_line, decl_end_line=decl_line))
            i += 1
            while i < len(lines):
                ax_line = lines[i].strip()
                if re.match(r"[a-z_]+\s*:", ax_line):
                    name = ax_line.split(":")[0].strip()
                    ax_entry = Entry("AXIOM", name, f"  AXIOM {ax_line}",
                                     thy_line=i + 1, decl_end_line=i + 1)
                    entries.append(ax_entry)
                    i += 1
                elif ax_line.startswith("and "):
                    i += 1
                elif ax_line == "" or TOPLEVEL_RE.match(lines[i]):
                    break
                else:
                    i += 1
            continue

        # --- Definitions ---
        if route == "def":
            rest = line[len(keyword):].strip()
            rest = re.sub(r"\s+where$", "", rest)
            # definition/abbreviation may carry an implicit name in a quoted
            # equation (`"lhs \<equiv> ..."`); read its LHS head when no label.
            parse_fn = (_parse_def_name
                        if keyword in ("definition", "abbreviation")
                        else _parse_name)
            name = parse_fn(rest)
            if name == "?" and not _strip_decl_prefix(rest, typevars=False):
                name = _lookahead_name(lines, i + 1, table, parse_fn)
            buf = [f"{tag} {rest}"]
            decl_end_line = decl_line
            i += 1
            open_quotes = rest.count('"') % 2
            past_where = False  # for `definition`/`abbreviation`: tracks whether
                                # the body's quoted RHS has begun, so we don't
                                # break at the type signature's closing quote.
            while i < len(lines):
                cline = lines[i]
                if BLANK_RE.match(cline):
                    break
                if _match_decl(cline, table):
                    break
                stripped = cline.strip()
                if stripped.startswith("\\<comment>") or stripped.startswith("text "):
                    break
                where_on_this_line = bool(re.search(r"\bwhere\b", stripped))
                buf.append(f"  {stripped}")
                open_quotes = (open_quotes + stripped.count('"')) % 2
                i += 1
                decl_end_line = i  # 1-indexed line just appended
                if keyword in ("definition", "abbreviation"):
                    # Break when the body's quoted RHS closes (after `where`).
                    if past_where and open_quotes == 0 and '"' in stripped:
                        break
                    if where_on_this_line:
                        past_where = True
            entries.append(Entry(tag, name, "\n".join(buf),
                                 thy_line=decl_line,
                                 decl_end_line=decl_end_line))
            continue

        # --- Lemmas / theorems / corollaries ---
        if route == "goal":
            rest = line[len(keyword):].strip()
            name = _parse_name(rest)
            buf = [f"{tag} {rest}"]
            decl_end_line = decl_line
            proof_line = 0
            # Named conjuncts: scan the `shows` region only.  `shows` may
            # appear inline on the decl line (one-liner) or on its own line.
            in_shows = bool(SHOWS_ANYWHERE_RE.search(rest))
            conjuncts: list[str] = (
                CONJUNCT_RE.findall(rest) if in_shows else [])
            i += 1

            while i < len(lines):
                cline = lines[i]
                stripped = cline.strip()
                if BLANK_RE.match(cline):
                    break
                if PROOF_RE.match(cline):
                    proof_line = i + 1
                    break
                if _match_decl(cline, table):
                    break
                if stripped.startswith("\\<comment>"):
                    i += 1
                    continue
                if SHOWS_AT_START_RE.match(stripped):
                    in_shows = True
                if in_shows:
                    conjuncts.extend(CONJUNCT_RE.findall(stripped))
                buf.append(f"  {stripped}")
                i += 1
                decl_end_line = i

            entries.append(Entry(tag, name, "\n".join(buf),
                                 thy_line=decl_line,
                                 decl_end_line=decl_end_line,
                                 proof_line=proof_line,
                                 conjuncts=conjuncts))
            continue

        i += 1

    return entries


# Outer commands that declare nothing and so are never indexed as entries, but
# which still bound the declaration above them.  `compute_spans` ends an entry at
# the next entry-or-section line; without these an `instance` proof, a `lemmas`
# alias or the `end` of an enclosing block falls INSIDE the preceding
# declaration's span.  Two things then go wrong: the span reported by
# `enclosing` / `outline` / `largest` is inflated, and a fact cited by the
# absorbed command lands in that declaration's own def-site range, where the
# call-graph scan discards it as a self-mention — so the cited fact reads as
# unused.  The canonical case is an `equal` instantiation:
#
#     instantiation foo :: equal begin
#     definition "equal_foo (x::foo) y = (x = y)"
#     instance by standard (simp add: equal_foo_def)
#     end
#
# where `equal_foo` swallows the very `instance` proof that cites it.
_SPAN_BOUNDARY_COMMANDS = frozenset({
    "begin", "end", "instance", "instantiation", "interpretation",
    "sublocale", "locale", "context", "declare", "lemmas", "notation",
    "no_notation", "syntax", "no_syntax", "translations",
    "code_printing", "export_code", "code_datatype", "code_reflect",
    "typedecl", "typedef", "consts", "print_translation",
})
_LEADING_CMD_RE = re.compile(r"^([a-z][a-z_0-9]*)")


def _structural_command_lines(
        lines: list[str],
        comment_ranges: list[tuple[int, int]] | None = None) -> list[int]:
    """1-indexed lines that open a span-bounding outer command.

    Fed to :func:`compute_spans` alongside the section lines, so a declaration
    ends where the next outer command begins rather than running on through it.
    See ``_SPAN_BOUNDARY_COMMANDS``.

    Boundary commands are matched in column 0 only, so an indented `end` closing
    a nested proof does not cut anything.  Lines inside a ``\\<comment>`` range
    are skipped — a commented-out command is prose, not a boundary (a ``(* end
    *)`` never matches the column-0 lowercase anchor to begin with).
    """
    masked: set[int] = set()
    for start, end in (comment_ranges or []):
        masked.update(range(start, end + 1))
    out: list[int] = []
    for line_no_0, line in enumerate(lines):
        line_no = line_no_0 + 1
        if line_no in masked:
            continue
        m = _LEADING_CMD_RE.match(line)  # column 0: introducer position
        if m is None or m.group(1) not in _SPAN_BOUNDARY_COMMANDS:
            continue
        # Report the boundary at the head of any blank run before the command,
        # so the preceding entry's span ends on its last real line rather than
        # on the separating blank — the same "no trailing blanks" rule the
        # entry-to-entry boundary already follows.
        b = line_no
        while b > 1 and not lines[b - 2].strip():
            b -= 1
        out.append(b)
    return out


def compute_spans(entries: list[Entry], section_lines: list[int],
                  total_lines: int) -> None:
    """Set thy_end on each entry to the line before the next entry-or-section.

    The boundary above an entry is the *next entry's* ``src_start`` — its
    leading `text` preamble if it has one, else its declaration line — so a
    following entry's docstring is charged to *that* entry, not folded into
    the preceding entry's span (the `[src-doc-attribution]` fix).  Run after
    ``_attach_preambles`` so ``src_start`` is known.

    ``structural`` is sorted, so the next boundary above an entry is a
    ``bisect`` away — the old ``[s for s in structural if s > e.thy_line]``
    rescanned the whole list per entry, making this O(entries^2).  That is
    invisible on a typical theory but the dominant parse cost on an
    entry-dense one (e.g. a file of thousands of short declarations).
    """
    structural = sorted({e.src_start for e in entries if e.thy_line > 0}
                        | set(section_lines))
    n = len(structural)
    for e in entries:
        # Bisect on the *declaration* line (not src_start): the boundary must
        # lie strictly after this entry's own decl, so an entry's own preamble
        # start never reads as its end.
        idx = bisect_right(structural, e.thy_line)
        e.thy_end = (structural[idx] - 1) if idx < n else total_lines


def _attach_preambles(entries: list[Entry], lines: list[str],
                      text_blocks: list[tuple[int, int]]) -> None:
    """Attach each leading `text` block to the entry it documents (preamble).

    Preamble: text block whose `end` line is within ~3 blank lines of an
    entry's `thy_line`.  Avoids attaching a giant top-of-file narrative
    to the very first entry hundreds of lines later.

    Runs *before* ``compute_spans`` — the preamble fixes the entry's
    ``src_start``, which `compute_spans` then uses as the boundary so the doc
    is charged to this entry, not the preceding one.
    """
    # --- preambles: text block → next entry, only if adjacent AND small ---
    # Both conditions matter: a 500-line section narrative just before the
    # first definition is NOT that definition's docstring; it's the chapter's
    # introduction.  See UTM.thy lines 28-530 for the canonical example.
    #
    # entry_starts is sorted, so the entry just below a block is a bisect away —
    # the old per-block linear scan over all entries was O(text_blocks x
    # entries), quadratic on a theory dense in both.
    PREAMBLE_MAX_LINES = 30
    entry_starts = sorted([(e.thy_line, e) for e in entries if e.thy_line > 0])
    starts_keys = [es for es, _ in entry_starts]
    n = len(entry_starts)
    for tb_start, tb_end in text_blocks:
        if tb_end - tb_start + 1 > PREAMBLE_MAX_LINES:
            continue  # too big to be a per-entry docstring
        idx = bisect_right(starts_keys, tb_end)  # first entry starting past tb_end
        if idx >= n:
            continue
        es, e = entry_starts[idx]
        # Are intervening lines (tb_end+1 .. es-1) all blank?
        gap = lines[tb_end:es - 1]
        if all(not l.strip() for l in gap) and len(gap) <= 3:
            e.preamble = (tb_start, tb_end)


def _attach_roadmaps(entries: list[Entry],
                     comment_lines: list[tuple[int, str]]) -> None:
    """Attach each in-proof \\<comment> line (roadmap) to its owning entry.

    Roadmap: \\<comment> line whose line number lies inside the entry's
    proof span [proof_line+1 .. thy_end].  Runs *after* ``compute_spans`` —
    it reads ``thy_end`` to bound the proof body.
    """
    # Spans are non-overlapping, so the only candidate is the entry whose
    # thy_line is the greatest <= cline; attach iff cline is in its proof body.
    # entry_starts is sorted, so the enclosing entry is a bisect away (the old
    # per-comment scan over all entries was O(comments x entries)).
    entry_starts = sorted([(e.thy_line, e) for e in entries if e.thy_line > 0])
    starts_keys = [es for es, _ in entry_starts]
    for cline, content in comment_lines:
        idx = bisect_right(starts_keys, cline) - 1
        if idx < 0:
            continue
        e = entry_starts[idx][1]
        if e.proof_line and e.proof_line < cline <= e.thy_end:
            e.roadmap.append((cline, content))


def _parse_one(thy: str, thy_path: Path,
               lines: list[str] | None = None) -> TheorySection:
    """Parse a theory's source into a fully-populated TheorySection.

    `lines`, when supplied, is already-read source parsed *in place of*
    reading `thy_path` from disk — the path taken by the `-` stdin sentinel,
    whose `thy_path` is synthetic (`<stdin>`) and has nothing to read.  In
    that case the section caches the lines so a later `source()` call never
    falls back to reading the non-existent path.
    """
    from_memory = lines is not None
    if lines is None:
        lines = thy_path.read_text().splitlines()
    entries = extract_entries(lines)
    outline = extract_sections(lines)
    text_blocks = extract_text_blocks(lines)
    comment_ranges = extract_comment_ranges(lines)
    comment_lines = extract_comment_lines(lines)
    # Preambles first: they fix each entry's src_start, which compute_spans
    # uses as the boundary so a leading doc is charged to the entry it
    # documents (not the preceding one).  Roadmaps need the resulting thy_end.
    _attach_preambles(entries, lines, text_blocks)
    compute_spans(entries,
                  [s[2] for s in outline]
                  + _structural_command_lines(lines, comment_ranges),
                  len(lines))
    _attach_roadmaps(entries, comment_lines)
    for e in entries:
        e.theory = thy
    # Compute body_end_line: for entries with a proof, walk forward from
    # proof_line through proof / by / qed / blank lines, stopping at the
    # next text \<open>...\<close> block or declaration.  For pure
    # declarations (no proof), body ends at decl_end_line.  Computed after
    # compute_spans because _proof_extent needs thy_end as a search bound.
    sec_for_extent = TheorySection(thy, thy_path, entries, thy_lines=len(lines))
    sec_for_extent._source_cache = lines
    for e in entries:
        if e.proof_line:
            e.body_end_line = _proof_extent(sec_for_extent, e.proof_line, e.thy_end)
        else:
            e.body_end_line = e.decl_end_line or e.thy_line
    sec = TheorySection(thy, thy_path, entries, thy_lines=len(lines),
                        outline=outline, text_blocks=text_blocks,
                        comment_ranges=comment_ranges)
    if from_memory:
        # No disk path to lazily re-read (stdin); pin the source we already have.
        sec._source_cache = lines
    return sec


def _parse_plain(thy: str, path: Path,
                 lines: list[str] | None = None) -> TheorySection:
    """Build a *plain* section for a non-`.thy` file (e.g. a design memo
    passed as a grep positional).  The Isabelle entry/section/comment
    grammar does not apply to Markdown or prose, so we deliberately skip
    `extract_entries` and friends: a plain section has no entries, no
    outline, and no text/comment ranges.  cmd_grep then degrades to
    ordinary line-based `grep` over it — no synthesised owning-entry
    labels, no live/comment classification (every match is reported).

    `lines`, when supplied, is already-read source (the stdin path), parsed
    in place of reading `path` — symmetric with `_parse_one`."""
    if lines is None:
        lines = path.read_text().splitlines()
    sec = TheorySection(thy, path, [], thy_lines=len(lines), is_thy=False)
    sec._source_cache = lines
    return sec


def _add_one_section(thy: str, thy_path: Path,
                     seen_paths: set[Path],
                     sections: list[TheorySection],
                     session: str | None = None) -> None:
    """Append a parsed section, deduplicating by resolved absolute path
    so that symlinked theories (e.g.\\ `link/Foo.thy`
    -> `sub/Foo.thy`) appear once even if both the symlink
    and the target are encountered.

    `.thy` paths are parsed with the full Isabelle entry grammar
    (`_parse_one`); any other path is parsed plainly (`_parse_plain`)
    so grep over a Markdown/prose file does not invent bogus entries.

    `session` records the owning Isabelle session (see
    `TheorySection.session`); the first section added for a given path
    wins, so the session tag matches the first ROOT that references the
    theory."""
    if not thy_path.exists():
        return
    resolved = thy_path.resolve()
    if resolved in seen_paths:
        return
    seen_paths.add(resolved)
    if thy_path.suffix == ".thy":
        sec = _parse_one(thy, thy_path)
    else:
        sec = _parse_plain(thy, thy_path)
    sec.session = session
    sections.append(sec)


def _scan_header_file(path: Path) -> dict[str, str]:
    r"""Scan only a theory's *header* for its `keywords` clause.

    Reads at most a few hundred lines (a header is short — `theory ... begin`),
    stopping at the theory's `begin`, so this is cheap even at AFP scale and
    never touches proof bodies."""
    head: list[str] = []
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for n, line in enumerate(f):
                head.append(line.rstrip("\n"))
                if n >= 400 or _HEADER_BEGIN_RE.match(line):
                    break
    except OSError:
        return {}
    return scan_keywords(head)


def _populate_custom_commands(pairs: list[tuple[str, Path]]) -> None:
    """Merge every theory header's custom-command table into the active
    root's union :data:`_CUSTOM_COMMANDS` — mirroring Isabelle's session-wide
    ``Keywords.++`` (Pure/Isar/keyword.scala:151).  This is what lets a theory
    that *uses* `AOT_theorem` be parsed correctly even though the command is
    *declared* in a different theory's header.  (Cleared by load_index before
    the scan; a name redeclared with a different kind takes the last seen.)"""
    for _name, path in pairs:
        if path.suffix == ".thy" and path.exists():
            _CUSTOM_COMMANDS.update(_scan_header_file(path))


def _sections_from_dir(root_dir: Path,
                       seen_paths: set[Path],
                       sections: list[TheorySection]) -> None:
    """Enumerate theories under `root_dir` and append parsed sections.

    Walks every ROOT file under `root_dir` (via `discover_roots`) and
    each session declared in each ROOT (via `parse_root_sessions`).
    Theories are resolved against the declaring session's directory,
    honouring its `in <subdir>` and `directories` clauses (so a theory
    a session declares to live `in "sub"` is found under `sub/`, not
    beside its ROOT file).

    Falls back to a recursive `*.thy` glob if no ROOTs are found
    (legacy behaviour for non-Isabelle-session directories).  Dedup
    by resolved path via `_add_one_section`.

    Two phases: first collect the theory list, then pre-scan all headers to
    build the custom-command union (so a use can precede its declaration in
    parse order), then parse each theory's entries.
    """
    roots = discover_roots(root_dir)
    pairs: list[tuple[str, Path]] = []
    session_of: dict[Path, str] = {}  # resolved path -> owning session name
    if roots:
        for root_path in roots:
            for session in parse_root_sessions(root_path):
                # In-entry import closure, not just ROOT-declared roots: an AFP
                # entry that declares leaf theories and imports the rest (e.g.
                # AODV declares 1, builds 73) is otherwise silently truncated.
                for name, thy_path in session_theories(session):
                    pairs.append((name, thy_path))
                    # First session that references a theory owns it, matching
                    # the path dedup in `_add_one_section` (first add wins).
                    session_of.setdefault(thy_path.resolve(), session.name)
    else:
        for thy_path in sorted(root_dir.rglob("*.thy")):
            pairs.append((thy_path.stem, thy_path))

    _populate_custom_commands(pairs)
    for name, thy_path in pairs:
        _add_one_section(name, thy_path, seen_paths, sections,
                         session=session_of.get(thy_path.resolve()))


def _proof_extent(sec: TheorySection, proof_line: int, thy_end: int) -> int:
    """Walk forward from proof_line, return last line that belongs to the proof.
    Stops at `text \\<open>...` blocks, section headers, next declarations, or
    end of file.  Returns proof_line itself for one-line proofs.
    """
    lines = sec.source()
    last = proof_line
    for line_no in range(proof_line + 1, thy_end + 1):
        if line_no > len(lines):
            break
        cline = lines[line_no - 1]
        stripped = cline.strip()
        # Stop at top-level documentation blocks (text \<open>...\<close>) but
        # NOT at in-proof Isar annotations (\<comment> \<open>...\<close>), which
        # are routine inside proof bodies.
        if stripped.startswith("text ") or stripped.startswith("text\\<open>"):
            break
        if SECTION_RE.match(cline):
            break
        if DECL_RE.match(cline):
            break
        if stripped:
            last = line_no
    return last
