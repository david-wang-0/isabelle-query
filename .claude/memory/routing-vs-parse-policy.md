---
name: routing-vs-parse-policy
description: "In isabelle-query, separate FILES routing (where bytes come from) from parse policy (whether to read as Isabelle); file extension is only evidence for the ambiguous case"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

For `query CMD FILES`-shaped commands, András wants two concerns kept separate in the implementation:

1. **Routing** — resolving a FILES token (`-` stdin / path / bare theory name) to its source. This is shared across ALL such commands via one resolver (`_resolve_file_source` + `FileSource`), so `lines` and the search family can't drift on what a token means.
2. **Parse policy** — whether to read a source with the Isabelle entry grammar. This is a property of the **command**, not the file: `largest`/`sorry` are intrinsically syntax-aware (entries *are* their output), `lines` is ignore-syntax (raw text), `grep` is the one genuinely-ambiguous case. Applied by `_section_from(src, parse)` with `parse` ∈ {"syntax","infer"} (a "plain" value is the open [[grep-plain]] todo).

**Why:** the file-extension heuristic should be *at most additional evidence for the ambiguous command* (`grep`: `.thy`→syntax, else plain, stdin→syntax), NEVER the primary switch for every command. Inferring parse mode from the extension uniformly is the anti-pattern he flagged. Quote: "some CMDs (like largest) imply syntax-aware, others (like lines) imply ignore-syntax... the filename extension heuristic... if anything it should be an additional piece of evidence when (like query grep) it is not clear what to do."

**How to apply:** when a new file-taking command or scope is added (e.g. `find-stmt`, or `outline`/`theory`/`defs` gaining PATH positionals under `[multi-name]`), route its FILES through `_resolve_file_source` and have it declare an explicit parse policy — don't re-derive parsing from the suffix. Matches the CLI contract's "one helper per shared feature" discipline.
