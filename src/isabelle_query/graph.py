"""Call graph, usage analysis, and the shared line-level lookups.

The third layer of the module DAG (above ``parsing``, below ``render`` /
``commands``).  Everything here answers a *usage* question over the parsed
index rather than a text question over source:

* line→entry attribution (``_build_line_index`` / ``_entry_at_line``) and the
  name indices (``_sections_by_theory`` / ``_entry_by_name``);
* the prose/def-site exclusion masks shared by single-name search and bulk
  graph construction (``_noise_spans`` / ``_noise_ranges`` / ``_build_def_sites``);
* the citation router (``_is_citation_name`` / ``_NON_CITATION``) and the
  single-pass name-level call graph (``_build_call_graph``);
* the proof-method census (``_scan_methods``), the router's complement — the
  tokens ``_is_citation_name`` rejects as fact edges are the method uses it
  tallies;
* the one breadth-first walk behind every ``-r`` form (``_bfs_depths``).

Depends only on ``model``, ``parsing`` (for the ``_line_mask`` primitive), and
the Isabelle namespace tables — never on rendering or the CLI.
"""

from __future__ import annotations

import re
from bisect import bisect_right
from collections import Counter
from collections.abc import Callable, Iterable
from operator import itemgetter

from isabelle_query import _isabelle_namespace as _isa_ns
from isabelle_query.model import (
    CallGraph,
    Entry,
    TheorySection,
    _CITABLE_TAGS,
    _DROP_NAMES_UPTO,
)
from isabelle_query.parsing import _line_mask

def _build_line_index(sections: list[TheorySection]
                      ) -> dict[str, list[tuple[int, int, Entry]]]:
    """For each theory, build a sorted list of (src_start, thy_end, Entry)
    for binary-search lookup of which entry owns a given line.  The span
    starts at ``src_start`` (the leading preamble, if any) so a doc line
    resolves to the entry it documents, not the preceding one."""
    index: dict[str, list[tuple[int, int, Entry]]] = {}
    for sec in sections:
        spans = [(e.src_start, e.thy_end, e) for e in sec.entries
                 if e.thy_line > 0]
        spans.sort()
        index[sec.theory] = spans
    return index


_FIRST = itemgetter(0)  # span start, for keyed bisect into a line index


def _entry_at_line(line_index: list[tuple[int, int, Entry]],
                   line_no: int) -> Entry | None:
    """Binary search for the entry whose [src_start, thy_end] contains line_no.

    `bisect` reads each probed span's start via `key=` (a C-level itemgetter),
    so the search touches O(log n) elements — the old form rebuilt a full
    `[s[0] for s in line_index]` keys list on *every* call, which at corpus
    scale (one call per source line) dominated the build profile.
    """
    idx = bisect_right(line_index, line_no, key=_FIRST) - 1
    if idx < 0:
        return None
    start, end, entry = line_index[idx]
    if start <= line_no <= end:
        return entry
    return None


def _sections_by_theory(sections: list[TheorySection]
                        ) -> dict[str, TheorySection]:
    """Index sections by theory name (theory → section)."""
    return {s.theory: s for s in sections}


def _entry_by_name(sections: list[TheorySection]
                   ) -> dict[str, tuple[str, Entry]]:
    """First-wins index of entry name → (theory, Entry).

    First-wins: when a name is defined in more than one theory the earliest
    section in load order owns the lookup — matching every call site that
    previously built this map inline.
    """
    by_name: dict[str, tuple[str, Entry]] = {}
    for sec in sections:
        for e in sec.entries:
            if e.name not in by_name:
                by_name[e.name] = (sec.theory, e)
    return by_name


def _noise_spans(sec: TheorySection) -> list[tuple[int, int]]:
    r"""Inclusive ``[lo, hi]`` line spans of `sec` that are NOT live source:
    top-level ``text``/``text_raw`` blocks, multi-line ``\<comment>``
    annotations, per-entry preambles, and the lexical non-Isar regions
    (``(* ... *)`` comments, ``\<^cancel>``, legacy ``{* ... *}`` verbatim and
    ML bodies — `parsing.extract_nonisar_ranges`).  The single definition of
    "prose, not proof" — `grep`, `methods`, the call graph (via
    `_noise_ranges`), and the proof-block drill-down all skip exactly these
    lines, so the notion can no longer drift between them.
    """
    return (list(sec.text_blocks) + list(sec.comment_ranges)
            + list(sec.nonisar_ranges)
            + [e.preamble for e in sec.entries if e.preamble])


def _noise_ranges(sections: list[TheorySection]) -> dict[str, list[range]]:
    r"""Per-theory ``range`` objects for the non-live (prose) line spans —
    each section's :func:`_noise_spans` as ``range``s for membership tests.
    Used by single-name search (`_find_callers`) and bulk graph construction
    (`_build_call_graph`) — the oracle shares it — so both treat
    ``text``/``\<comment>``/preamble mentions as documentation, not calls.
    """
    return {sec.theory: [range(lo, hi + 1) for lo, hi in _noise_spans(sec)]
            for sec in sections}


def _build_def_sites(sections: list[TheorySection],
                     names: set[str] | None = None,
                     ) -> dict[str, dict[str, set[range]]]:
    """Per-theory map of definition-site line ranges, keyed by entry name.

    Used to exclude the definition itself from a search for references
    to that name.  When ``names`` is given, only those names are tracked;
    otherwise every entry with a source location is included.

    Result shape: ``def_sites[theory][name] = {range(thy_line, thy_end+1), ...}``
    """
    def_sites: dict[str, dict[str, set[range]]] = {}
    for sec in sections:
        site_map: dict[str, set[range]] = {}
        for e in sec.entries:
            if e.thy_line <= 0:
                continue
            if names is None or e.name in names:
                site_map.setdefault(e.name, set()).add(
                    range(e.thy_line, e.thy_end + 1))
            # A named conjunct's declaration site is its parent's span, so a
            # `callers CONJUNCT` search excludes the `shows ... and C:` line.
            # Restricted to explicitly-queried names (never the names=None
            # broad pass) so conjuncts don't leak into the call-graph universe.
            if names is not None:
                for c in e.conjuncts:
                    if c in names:
                        site_map.setdefault(c, set()).add(
                            range(e.thy_line, e.thy_end + 1))
        def_sites[sec.theory] = site_map
    return def_sites


# Method-argument modifiers parsed inline by individual methods, so they have
# no declaration site of their own (and are absent from _isabelle_namespace);
# a short, auditable tier-2 list to go with the source-derived namespaces.
_ARG_MODIFIERS = frozenset({"add", "del", "only", "OF", "THEN"})

# The router's namespace tables — the single reconfigurable source of truth for
# every consumer (this module, `commands`' method verb, `shape`'s identifier
# classifier).  They start bound to the committed static table so **import stays
# pure**: importing the package spawns no Isabelle and stats no heap, which is
# what keeps `query` startup sub-100ms and — crucially — keeps every direct-call
# test deterministic regardless of whether Isabelle is installed on the machine
# running the suite.  `configure_namespace()` rebinds them once, at CLI dispatch,
# after a caller has resolved a runtime-dumped table; nothing rebinds them at
# import time.
_PROOF_METHODS = _isa_ns.PROOF_METHODS
_ATTRIBUTES = _isa_ns.ATTRIBUTES
_KEYWORDS = _isa_ns.KEYWORDS
# Tokens that are never a *fact citation*: proof methods (`by simp`),
# attributes (`[OF g]`), keywords (`proof`, `and`), inline argument modifiers,
# and bare numerals.  A call-graph edge is created only for a name that passes
# _is_citation_name, so an entry that merely happens to be *named* after one of
# these — Isabelle_Meta_Model's `definition "simp"`, a `definition "1 = ..."`,
# an ML `fun lemma` misread as a command — does not collect a spurious in-edge
# from every `by simp` / numeral in the corpus.  The entry still exists for
# show/largest/defs; it is simply not a node in the *citation* graph.  Method
# occurrences are recovered separately by the `methods` query, so this routes
# rather than discards.
_NON_CITATION = (_PROOF_METHODS | _ATTRIBUTES | _KEYWORDS | _ARG_MODIFIERS)


def configure_namespace(methods, attributes, keywords) -> None:
    """Rebind the router's namespace tables and rebuild the derived reject-set.

    The one seam through which a resolved (e.g. runtime-dumped) Isabelle
    namespace reaches the router.  Call it **only from the CLI dispatch path**,
    never at import: the module-level bindings default to the committed static
    table, so importing the package spawns nothing and every direct-call test
    sees a fixed table.  `commands` (the `methods` verb) and `shape` (its
    identifier classifier) read these same globals late-bound, so one call
    reconfigures all three consumers coherently.  `keywords` is passed through
    unchanged — the dump supplies only methods/attributes; keywords stay the
    declarative Pure.thy table, which there is no reason to reinvent.
    """
    global _PROOF_METHODS, _ATTRIBUTES, _KEYWORDS, _NON_CITATION
    _PROOF_METHODS = frozenset(methods)
    _ATTRIBUTES = frozenset(attributes)
    _KEYWORDS = frozenset(keywords)
    _NON_CITATION = _PROOF_METHODS | _ATTRIBUTES | _KEYWORDS | _ARG_MODIFIERS



def _is_citation_name(name: str, drop_upto: int = _DROP_NAMES_UPTO) -> bool:
    """Whether a name can denote a cited fact, vs a method/attribute/keyword/
    numeral token *or a name too short to tell apart from a term variable*.
    Shared by the fast builder and the brute-force oracle so both implement
    the same citation semantics.

    ``drop_upto`` filters out citation names of length <= it.  A length-1
    token (`x`, `a`, `f`, the wildcard `_`) is a bound variable in nearly
    every proof, so by default (``drop_upto`` = 1) length-1 names are not
    citation nodes — on the AFP they carry ~28% of all in-edges across 51
    universal-variable names, essentially all noise.  Length-2+ is kept,
    preserving genuine short lemma names (`le`, `id`, `or`).  ``drop_upto`` = 0
    disables the length filter (keep single-char names); 2 also drops 2-char
    names (more aggressive).  See ``scripts/analyze_citation_names.py`` for
    the AFP evidence; the ``--drop-names-upto N`` flag sets it.  The
    method/keyword/numeral router is independent of ``drop_upto``.
    """
    return (len(name) > drop_upto
            and name not in _NON_CITATION and not name.isdigit())


_WORD_RE = re.compile(r"[\w']+")


def _shadowed_uses_on_line(line: str, names: set[str],
                           derived: bool = False) -> set[str]:
    """Which of `names` this line genuinely USES.

    `names` are declared entries whose spelling is also a proof method or
    attribute (`lemma foo` where `foo` is an Eisbach method; `definition simp`).
    The position-blind scan cannot tell `by simp` from a use of such an entry,
    which is why these names were once dropped from the graph altogether — at
    the cost of every real citation of them.  Position settles it, and only for
    this rare subset, so the line is re-read only when one is present.

    A mention counts as a use when it is either

    * an explicit fact citation — `using foo`, `by (rule foo)`, `simp add: foo`
      (:func:`_cited_facts_on_line`, which reads fact-argument positions only), or
    * inside a quoted proposition or cartouche — `lemma "simp x = y"` — where
      the token is a constant or statement text, never a method invocation.

    Everything else — `by simp`, `apply (auto simp: h)`, `[symmetric]` — is the
    method or attribute of that name doing its job, and mints no edge.
    """
    cited, _ = _cited_facts_on_line(line)
    if derived:
        cited = cited | {n for n in names
                         if n + "_def" in cited or n + "_defs" in cited}
    used = names & cited
    rest = names - used
    if rest and ('"' in line or "\\<open>" in line):
        terms = " ".join(a or b for a, b in _PROP_TEXT_RE.findall(line))
        if terms:
            used = used | (rest & set(_WORD_RE.findall(terms)))
    return used


def _build_call_graph(sections: list[TheorySection],
                      drop_upto: int = _DROP_NAMES_UPTO,
                      derived: bool = False) -> CallGraph:
    """Single-pass scan building a full name-level call graph.

    Uses the shared filtering helpers (`_noise_ranges`,
    `_build_def_sites`): skips text/comment blocks, definition sites, and
    antiquotation-only mentions.  ``drop_upto`` is forwarded to
    :func:`_is_citation_name` — length-1 names (variable collisions) are
    excluded by default; see that function and ``--drop-names-upto``.

    ``derived`` treats Isabelle's definitional spellings (``foo_def``,
    ``foo_defs``) as citations of ``foo``.  Off by default, because the graph is
    over FACTS and ``foo_def`` is a different fact from ``foo``; only
    :func:`commands.cmd_unused` turns it on, where the question is whether the
    DECLARATION is dead.  See the note there.
    """
    # 1. Collect candidate names.  A name too short to tell from a term
    #    variable, or a bare numeral, is not a citable fact, so the universal
    #    variable `x` mints no edges.
    #
    #    A name that is ALSO a proof method / attribute / keyword is admitted,
    #    but into `shadowed`: its mentions are checked positionally below.  It
    #    used to be dropped outright, which stopped `by simp` minting edges to a
    #    `definition simp` — but also erased every genuine citation of any entry
    #    whose name collides with the bound table.  The table is a union over
    #    sessions, and `HOL-Eisbach` exports the methods of its own `Tests`
    #    theory, so `lemma foo` disappeared from `callers` and `unused`
    #    entirely.  Position tells the two apart; a name never can.
    name_set: set[str] = set()
    shadowed: set[str] = set()
    for sec in sections:
        for e in sec.entries:
            if (e.tag in _CITABLE_TAGS and e.name != "?"
                    and len(e.name) > drop_upto and not e.name.isdigit()):
                name_set.add(e.name)
                if e.name in _NON_CITATION:
                    shadowed.add(e.name)

    # 1b. Derived-fact spellings.  Isabelle mints `foo_def` from `definition
    #     foo`, and citing it IS a use of `foo` — often the only one, since an
    #     `equal` instance proof cites nothing but `equal_foo_def`.  The dotted
    #     families (`foo.simps`, `foo.induct`) need no help: the `[\w']+`
    #     tokeniser already splits them, leaving a bare `foo` to match.  The
    #     underscore family does not split, so map it back explicitly.  An entry
    #     genuinely named `foo_def` keeps its own identity — only spellings that
    #     are not themselves entries are treated as derived.
    derived_base: dict[str, str] = {}
    for n in (name_set if derived else ()):
        for suffix in ("_def", "_defs"):
            spelling = n + suffix
            if spelling not in name_set:
                derived_base[spelling] = n

    # 2. Build def-site and text-block exclusion ranges.
    def_sites = _build_def_sites(sections, name_set)
    text_ranges = _noise_ranges(sections)

    # 3. Build line-to-entry index for caller attribution.
    line_index = _build_line_index(sections)

    # 4. Reference-extraction patterns.
    #    antiq_re strips doc antiquotations (@{thm foo}) so a name cited only
    #    in rendered documentation is not counted as a proof-body call.
    antiq_re = re.compile(r'@\{(?:text|thm|term|const)\s+["\']?\w+["\']?\}')
    #    The old per-name search matched a name wherever it sat between
    #    non-`[\w']` characters.  Because `\` (the start of a \<...> symbol)
    #    is itself non-`[\w']`, a name can match two ways, and we must
    #    extract both to reproduce every edge without inventing any:
    #      * sym_re — maximal runs that include \<...> symbol tokens, so a
    #        symbolic name like `merge_rt_F\<^sub>m` is one token (a plain
    #        [\w'] split would lose it);
    #      * word_re — maximal [\w'] runs, so a bare name that abuts a symbol
    #        (`iso_transaction` in `iso_transaction\<^sub>h`) is still found.
    #    Names with other non-identifier characters (beta-C-cor:3) are written
    #    double-quoted at the use site, so we also look up whole quoted
    #    spellings.  All three hashed into name_set are the linear-time
    #    equivalent of the per-name boundary search.
    sym_re = re.compile(r"(?:\\<\^?\w+>|[\w'])+")
    word_re = re.compile(r"[\w']+")
    quoted_re = re.compile(r'"([^"]+)"')

    # 5. Single linear pass: O(total source size), not O(lines x names).
    #    Tokenise each line once and intersect with the name set, rather
    #    than testing every one of ~10^5 names against the line.
    callers: dict[str, set[str]] = {n: set() for n in name_set}
    callees: dict[str, set[str]] = {}

    # Bind the per-line hot callables to locals: this loop runs once per source
    # line (millions of times), and a local is a fast LOAD_FAST vs an attribute
    # lookup on each.
    ns_inter = name_set.intersection
    derived_inter = set(derived_base).intersection
    antiq_sub = antiq_re.sub
    word_findall = word_re.findall
    sym_findall = sym_re.findall
    quoted_findall = quoted_re.findall

    for sec in sections:
        # The redacted view (`live_source`), not the raw source: a comment, an
        # `\<^cancel>` region or an inline ML body that SHARES its line with
        # live proof text is blanked in place, so `by simp (* see foo *)` stops
        # citing `foo`.  Nothing else in this loop changes — the redaction
        # preserves every line and column, so the mask below and the 1-indexed
        # arithmetic still address the same characters.
        lines = sec.live_source()
        t_ranges = text_ranges.get(sec.theory, [])
        d_map = def_sites.get(sec.theory, {})
        idx = line_index.get(sec.theory, [])
        # Flatten the prose ranges into a 1-indexed line mask: a single O(1)
        # lookup per line replaces the old `any(line_no in r for r in t_ranges)`
        # rescan (~65M range tests at AFP scale).  Slice-assignment marks each
        # range C-side; the +2 pad keeps line_no == len(lines) in bounds.
        text_mask = _line_mask(len(lines),
                               ((r.start, r.stop - 1) for r in t_ranges))
        for line_no_0, line in enumerate(lines):
            line_no = line_no_0 + 1
            if text_mask[line_no]:
                continue
            # Strip doc antiquotations only when one is present; otherwise the
            # sub is a no-op that still scans the whole line.
            stripped = antiq_sub('', line) if '@{' in line else line
            # Candidate referenced names on this line.  word_re ([\w'] runs) is
            # always needed; sym_re differs from it only where a \<...> symbol
            # appears, and quoted_re only where a " does — so the two extra
            # findalls run on just those lines, not every line.  The union is
            # identical to scanning all three unconditionally (the oracle's
            # reference), but skips the provably-redundant passes.
            words = word_findall(stripped)
            cand = ns_inter(words)
            # `foo_def` resolves to `foo` (see derived_base).  Guarded on the map
            # being non-empty so the default path pays a truthiness test rather
            # than a set intersection on every line of every theory.
            if derived_base:
                dv = derived_inter(words)
                if dv:
                    cand = cand | {derived_base[d] for d in dv}
            if '\\<' in stripped:
                cand |= ns_inter(sym_findall(stripped))
            if '"' in stripped:
                cand |= ns_inter(quoted_findall(stripped))
            if not cand:
                continue
            # A candidate whose name is also a method/attribute has to earn its
            # edge positionally.  Guarded so the ordinary line pays one set
            # intersection, not the re-read.
            if shadowed:
                sh = cand & shadowed
                if sh:
                    cand = (cand - sh) | _shadowed_uses_on_line(line, sh, derived)
                    if not cand:
                        continue
            caller_entry = _entry_at_line(idx, line_no)
            if caller_entry is not None and caller_entry.name == "?":
                continue
            # A citation outside every indexed entry is still a real use: the
            # span-bounding outer commands (`instance`, `lemmas`, `declare`,
            # `code_printing`, `export_code`) cite facts but declare nothing, so
            # they own no lines.  Dropping their citations makes the cited fact
            # read as unused — an `equal` instance proof is the whole reason its
            # own `equal_*` definition exists.  Attribute them to a synthetic
            # per-theory top-level caller so the edge exists and carries a place.
            caller_name = (caller_entry.name if caller_entry is not None
                           else f"{sec.theory}:<toplevel>")
            for name in cand:
                d_ranges = d_map.get(name)
                if d_ranges and any(line_no in r for r in d_ranges):
                    continue
                callers[name].add(caller_name)
                callees.setdefault(caller_name, set()).add(name)

    return CallGraph(callers=callers, callees=callees, all_names=name_set)


# A proof method is introduced by one of the three pure proof keywords
# `by` / `apply` / `proof`; the method name is the first token after it
# (optionally wrapped in an opening `(`).  Anchoring on the introducer is
# what makes the scan precise: the method namespace contains short,
# variable-colliding names (`N`, `order`, `field`, `split`, `all`), but in
# *introducer position* even a one-letter token is unambiguously the method.
# Trade-off: this counts the initial method of each `by`/`apply`/`proof`, so
# combinator-chained (`by (induct x) auto`) and line-wrapped methods are
# undercounted — never over-counted, which keeps the ranking trustworthy.
_METHOD_INTRO_RE = re.compile(r"\b(?:by|apply|proof)\b\s*\(?\s*([\w']+)")


def _scan_methods(sections: list[TheorySection], only: str | None = None,
                  ) -> tuple[Counter, list[tuple[str, int, "Entry | None", str]]]:
    """Tally proof-method uses across live theory source.

    Returns ``(counts, located)``:

    * ``counts`` — :class:`collections.Counter` ``{method: occurrences}`` over
      every ``by`` / ``apply`` / ``proof`` introducer on a *live* line (not a
      ``text \\<open>...\\<close>`` block, a ``\\<comment>`` annotation, or a
      per-entry preamble — so prose like "apply the rule" is not mined).
    * ``located`` — ``[(theory, line_no, owning_entry, line_text)]`` for the
      method named by ``only`` (empty when ``only`` is None), the method
      analogue of :func:`_find_callers`.

    This is the complement of the citation router: the tokens
    :func:`_is_citation_name` declines to treat as fact-graph edges are
    exactly the method uses surfaced here.
    """
    methods = _PROOF_METHODS
    counts: Counter = Counter()
    located: list[tuple[str, int, Entry | None, str]] = []
    line_index = _build_line_index(sections)
    intro_finditer = _METHOD_INTRO_RE.finditer
    for sec in sections:
        # Scan the redacted view, report the real one: `by simp (* or apply
        # auto *)` must not count `auto` as a method use, but the located hit
        # has to show the user their actual line, blanks included nowhere.
        lines = sec.live_source()
        raw = sec.source()
        # "Live" = not inside a text block, multi-line \<comment>, or preamble
        # (the same notion `_grep_sections` uses), so an `apply`/`by` mentioned
        # in prose does not register as a method use.  A 1-indexed line mask
        # gives O(1) liveness per line, vs rescanning every noise range (the
        # same flattening the call-graph build uses).
        noise_mask = _line_mask(len(lines), _noise_spans(sec))
        idx = line_index.get(sec.theory, [])
        for line_no_0, line in enumerate(lines):
            line_no = line_no_0 + 1
            if noise_mask[line_no]:
                continue
            # The introducer regex requires one of these whole words, so its
            # letters must be present — a cheap necessary-condition guard skips
            # the regex on the many lines that hold no proof introducer at all.
            if 'by' not in line and 'apply' not in line and 'proof' not in line:
                continue
            hit_only = False
            for m in intro_finditer(line):
                tok = m.group(1)
                if tok in methods:
                    counts[tok] += 1
                    if tok == only:
                        hit_only = True
            if hit_only:
                located.append((sec.theory, line_no,
                                _entry_at_line(idx, line_no),
                                raw[line_no_0].rstrip()))
    return counts, located


def _leading_method(line: str) -> str:
    """The first proof method named on ``line`` — the token after a ``by`` /
    ``apply`` / ``proof`` introducer, when it is a recognised proof method.

    Reuses the method-census primitive (:data:`_METHOD_INTRO_RE` + the
    ``PROOF_METHODS`` namespace) so a step's *discharge* method is classified
    exactly as :func:`_scan_methods` counts it — no second parser (the shared
    home for the positional method notion, as ``_cited_facts_on_line`` is for
    citations).  Returns ``""`` when the line introduces no recognised method
    (``by (rule r)`` -> ``"rule"``; ``qed`` / a bare ``.`` / ``proof -`` ->
    ``""``).  Line-anchored like the width scanner: a method wrapped onto a
    continuation line is not seen (undercount, never overcount)."""
    for m in _METHOD_INTRO_RE.finditer(line):
        if m.group(1) in _PROOF_METHODS:
            return m.group(1)
    return ""


def _bfs_depths(neighbors: Callable[[str], Iterable[str]],
                seeds: Iterable[str], *, seed_depth: int = 0) -> dict[str, int]:
    """Breadth-first shortest-path depths from `seeds`, over a graph given as a
    `neighbors(node) -> iterable of adjacent nodes` callback.

    Returns ``{node: depth}`` *including* the seeds, which sit at ``seed_depth``;
    each successive ring is one deeper.  The depth convention is the caller's,
    made explicit by ``seed_depth`` rather than baked in:

      * ``seed_depth=0`` — the seed is depth 0 (the entry-level call closures,
        ``callers -r`` / ``callees -r``, which pop the seed afterward).
      * ``seed_depth=-1`` — the seed is a phantom hop so its *direct* neighbours
        are depth 0 ("direct"), the import-graph convention (``deps -r`` /
        ``uses -r``, which pop the seed too).

    The callback — rather than a prebuilt map — is what lets one BFS serve both
    a stored adjacency (the call graph; reverse imports) and a *lazily resolved*
    one (forward imports, whose resolver records out-of-project edges as a side
    effect).  Level-synchronised with a visited guard, so it is safe on any
    graph (DAG or cyclic) and yields true shortest-path depth.
    """
    depths: dict[str, int] = {}
    frontier = list(seeds)
    depth = seed_depth
    while frontier:
        nxt: list[str] = []
        for node in frontier:
            if node in depths:
                continue
            depths[node] = depth
            nxt.extend(neighbors(node))
        frontier = nxt
        depth += 1
    return depths


# ---------------------------------------------------------------------------
# Isar command keyword families and positional fact-citation extraction
# ---------------------------------------------------------------------------
#
# The call graph above answers "which entries MENTION name X" — position-blind:
# a name in a statement `"foo = foo"` counts as much as one in `using foo`.
# A different, narrower question is "which facts does this proof step CITE" —
# the arguments of `from`/`using`/`with`/`unfolding` and of the closing method.
# `_cited_facts_on_line` is the shared home for that positional notion: the
# width fan-in metric (M5a) aggregates it per step, and a future per-step
# `callers` view can reuse it.  It is deliberately conservative — it counts only
# high-confidence fact positions and flags any method shape it cannot classify,
# so a census can report method-syntax coverage (docs/shape-measures.md's M5a `null` +
# coverage requirement).

# The four Isar proof-command families (docs/shape-measures.md's "Definitions").  Defined
# here in the shared analysis layer so both the width step classifier and the
# fact extractor read one list.
GOAL_KEYWORDS = frozenset({
    "have", "show", "hence", "thus", "obtain", "consider",
    "also", "finally", "interpret",
})
CONTEXT_KEYWORDS = frozenset({
    "fix", "assume", "presume", "define", "let", "case",
})
PLUMBING_KEYWORDS = frozenset({
    "from", "using", "with", "note", "moreover", "ultimately", "then",
})
CLOSING_KEYWORDS = frozenset({
    "by", "apply", "done", "qed",
})

# Prefix/suffix fact-list keywords whose following identifiers are cited facts.
# `note` is excluded — it *introduces* a fact (`note x = ...`), it does not cite.
_CITE_LIST_WORDS = frozenset({"from", "with", "using", "unfolding"})
# A fact list runs until a goal/closing keyword (`from a have`, `using a by`) —
# or until the *next* cite keyword, so a chain (`using X unfolding Y`) ends the
# first list rather than swallowing `unfolding` as one of X's facts.
_FACT_LIST_STOP = (GOAL_KEYWORDS | CLOSING_KEYWORDS | _CITE_LIST_WORDS
                   | {"proof", "and"})

# Methods whose *bare* (colon-free) arguments are fact names, not terms/flags:
# `by (rule conjI)`, `by (metis a b)`, `by (unfold d)`.
_RULE_METHODS = frozenset({
    "rule", "erule", "drule", "frule", "intro", "elim", "dest",
    "metis", "meson", "subst", "unfold", "fold",
})
# Methods whose bare arguments are terms / flags / induction variables — never
# facts.  A bare arg here is a *covered* non-fact, not an unclassifiable token.
_TERM_ARG_METHODS = frozenset({
    "simp", "simp_all", "auto", "blast", "fastforce", "force", "fast",
    "clarsimp", "clarify", "safe", "linarith", "arith", "presburger",
    "algebra", "argo", "order", "eval", "normalization", "cases", "case_tac",
    "induct", "induction", "induct_tac", "coinduct", "coinduction",
    "standard", "rule_tac", "subgoal", "hypsubst", "-", "goal_cases",
})
# `NAME:` markers after which the trailing identifiers (until the next marker /
# structural token) are facts: `simp add: f g`, `auto simp: h`, `intro: i`.
_FACT_MARKER_WORDS = frozenset({
    "simp", "add", "del", "intro", "dest", "elim", "cong", "split",
})
# Attribute-position fact composers: the bracket attributes whose arguments are
# *facts*, not terms/flags — `r[OF a b]`, `r[THEN t]`, `d[unfolded e_def]`,
# `d[folded e_def]`, `t[simplified s]`.  Every other attribute (`of`, `where`,
# `symmetric`, `simp`, `rule_format`, ...) takes terms or is a bare flag, so it
# contributes no citation; see :func:`_consume_attr_block`.
_ATTR_FACT_WORDS = frozenset({"OF", "THEN", "unfolded", "folded", "simplified"})

# Strip quoted propositions and cartouches so their internal tokens are never
# mistaken for cited facts (`have "using x" by ...` cites nothing from the term).
_DQUOTE_STRIP_RE = re.compile(r'"[^"]*"')
_CARTOUCHE_STRIP_RE = re.compile(r"\\<open>.*?\\<close>")
# The same two regions, captured rather than blanked: the *term* text of a line.
# A name occurring here is being used as a constant or in a statement, which is
# a real use even when that name also happens to be a proof method.
_PROP_TEXT_RE = re.compile(r'"([^"]*)"|\\<open>(.*?)\\<close>')
# Token stream for the fact walk: names (with internal dots for qualified
# spellings), the `..` proof, and the structural chars that delimit method args.
_FACT_TOK_RE = re.compile(
    r"\.\.|(?:\\<\^?\w+>|[\w'])(?:\\<\^?\w+>|[\w'.])*|[():,|\[\]]|:")
# Structural tokens that never name a fact.
_STRUCTURAL = frozenset({"(", ")", "[", "]", "|", ",", ":", ".."})


def _strip_props(text: str) -> str:
    """Blank out quoted / cartouche propositions so their tokens are not read as
    cited facts."""
    text = _DQUOTE_STRIP_RE.sub(" ", text)
    return _CARTOUCHE_STRIP_RE.sub(" ", text)


def _looks_like_fact(tok: str) -> bool:
    r"""A candidate fact token is kept unless it cannot be an Isabelle fact name:
    a structural token, the lone wildcard ``_``, or a token that does not begin
    like an identifier — one led by a digit or a prime.

    An Isabelle long-identifier starts with a letter, an underscore, or a symbol
    token (`\<phi>_def`), never a digit or a `'`.  So a digit-led token is a
    numeral or a statement-text artifact (`31`, `0..`, `1.`, a glued `33have`
    from a spacing quirk), and a prime-led token is a type variable (`'a`) — both
    are terms/labels, not cited facts, and idf would otherwise weight such a
    df-1 artifact maximally.  Symbol-led names (`\<phi>_def`) and short labels
    (`a`, an `assumes a:`; `le`, a rule) are kept: unlike the position-*blind*
    citation graph, this extractor only inspects fact-argument positions, so a
    single-char token there *is* a fact and no length floor applies.  The bare
    ``_`` is the term placeholder (`rule r[OF _ h]`), never a fact."""
    return (tok != "_" and tok not in _STRUCTURAL
            and not tok[:1].isdigit() and not tok.startswith("'"))


def _consume_attr_block(toks: list[str], i: int, facts: set[str]) -> int:
    r"""Consume a postfix attribute block — ``toks[i]`` is the opening ``[`` —
    adding to ``facts`` only the arguments of the *fact-composing* attributes
    (:data:`_ATTR_FACT_WORDS`: ``OF`` / ``THEN`` / ``unfolded`` / ``folded`` /
    ``simplified``).  A term- or flag-valued attribute (``of``, ``where``,
    ``symmetric``, a bare declaration) contributes nothing, so its inner tokens
    — bound variables, ``\<phi>``, ``and``, numerals — no longer leak as facts.

    Returns the index just past the matching ``]``.  Nested brackets are tracked
    by depth; a comma resets to the next attribute head; a ``NAME:`` sub-marker
    (``[simplified add: f]``) is skipped so the marker word is not itself read as
    a fact.
    """
    n = len(toks)
    depth = 0
    take = False                     # inside a fact-composing attribute
    while i < n:
        t = toks[i]
        if t == "[":
            depth += 1
            take = False
        elif t == "]":
            depth -= 1
            take = False
            if depth == 0:
                return i + 1
        elif t in _ATTR_FACT_WORDS:
            take = True
        elif t == ",":
            take = False             # a fresh attribute head follows
        elif take and _looks_like_fact(t) \
                and not (i + 1 < n and toks[i + 1] == ":"):
            facts.add(t)
        i += 1
    return i                         # unbalanced — consumed to end of line


def _cited_facts_on_line(line: str) -> tuple[set[str], bool]:
    r"""Fact names cited in the citation positions of one proof line, plus a
    ``covered`` flag.

    Extracts the arguments of ``from`` / ``with`` / ``using`` / ``unfolding``,
    the ``NAME:``-marked fact lists inside a method (``simp add: f g``), the bare
    arguments of a rule-style method (``rule r``, ``metis a b``), and the
    fact-composing attribute blocks (``[OF a]`` / ``[THEN t]`` /
    ``[unfolded d]``).  Quoted propositions are stripped first, so a term that
    merely *contains* a keyword is ignored.

    Two shapes that used to leak spurious tokens are handled exactly:
    a **chained cite keyword** (``using X unfolding Y``) ends the first fact
    list rather than being read as one of ``X``'s facts; and a
    **term-/flag-valued attribute** (``[of t]``, ``[where x=t]``,
    ``[symmetric]``) contributes nothing, so its inner terms — bound variables,
    ``\<phi>``, ``and`` — are not mistaken for cited facts (:func:`_consume_attr_block`).

    ``covered`` is ``False`` when the line applies a method that is neither a
    known rule-method nor a known term-arg method *and* passes bare arguments —
    i.e. a shape whose arguments cannot be classified as facts-or-not.  The
    census sums this into a method-syntax coverage statistic (docs/shape-measures.md).
    """
    toks = _FACT_TOK_RE.findall(_strip_props(line))
    facts: set[str] = set()
    covered = True
    i, n = 0, len(toks)
    in_method = False       # inside a `by`/`apply` method expression
    cur_method: str | None = None
    while i < n:
        t = toks[i]
        # from / with / using / unfolding fact list (prefix or suffix).  Each
        # listed fact may carry its own attribute block (`using assms[OF x]`),
        # whose OF/THEN premises join the list; a chained cite keyword or a
        # goal/closing keyword ends it.
        if t in _CITE_LIST_WORDS:
            j = i + 1
            while j < n and toks[j] not in _FACT_LIST_STOP:
                tj = toks[j]
                if tj == "[":
                    j = _consume_attr_block(toks, j, facts)
                    continue
                if tj in _STRUCTURAL:
                    break
                if _looks_like_fact(tj):
                    facts.add(tj)
                j += 1
            i = j
            continue
        if t in ("by", "apply"):
            in_method = True
            cur_method = None
            i += 1
            continue
        if in_method:
            # A postfix attribute block on the preceding fact/method: only its
            # OF/THEN/unfolded/... arguments cite; `[of t]`/`[where x=t]` do not.
            if t == "[":
                i = _consume_attr_block(toks, i, facts)
                continue
            # A method marker `NAME:` — trailing tokens are facts, until the
            # next marker, a method-group close/separator, or a closing keyword.
            if t in _FACT_MARKER_WORDS and i + 1 < n and toks[i + 1] == ":":
                j = i + 2
                while j < n:
                    tj = toks[j]
                    # Any modifier keyword begins a new group and ends this fact
                    # list — including the two-word `simp add:` / `simp del:`
                    # form, where `simp` is not itself followed by `:`.
                    if tj in _FACT_MARKER_WORDS:
                        break
                    if tj in CLOSING_KEYWORDS or tj in (")", "]", "|", ","):
                        break                       # group close / separator
                    if tj == "[":
                        j = _consume_attr_block(toks, j, facts)
                        continue
                    if _looks_like_fact(tj):
                        facts.add(tj)
                    j += 1
                i = j
                continue
            if t in _STRUCTURAL:
                if t in ("(", ")", "|", ","):
                    cur_method = None   # a fresh method head may follow
                i += 1
                continue
            if cur_method is None:
                cur_method = t          # the method name
                i += 1
                continue
            # A bare argument to the current method.
            if cur_method in _RULE_METHODS:
                if _looks_like_fact(t):
                    facts.add(t)
            elif cur_method in _TERM_ARG_METHODS:
                pass                    # a term / flag, not a fact
            else:
                covered = False         # unknown method with bare args
            i += 1
            continue
        i += 1
    return facts, covered
