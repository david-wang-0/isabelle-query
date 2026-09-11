# Maybe

Optional ideas, not scheduled implementation work.

## `[semantic-resolution]` PIDE markup indexing

An optional semantic layer alongside lightweight source-only query, which must
remain useful on theories that do not build and during refactors.

- Reuse matching saved PIDE markup or live processed snapshots to collect resolved
  references and build a reverse index. Missing/current markup requires Isabelle
  processing; ordinary queries must never start that work implicitly.
- Keep source-reference locations, named theorem dependencies and constant/type
  membership in exported terms as separate relations. Inversion is straightforward;
  obtaining complete, current semantic evidence is the expensive part.
- Extract position-preserving references directly from snapshots: current
  `find_entities` drops occurrence locations, and full-markup JSON does not explicitly
  retain each entry's enclosing source range.
- Persist bounded session/theory shards with build/source provenance and explicit
  missing, stale or unresolved coverage. Partial coverage cannot establish global
  absence. No whole-AFP heap or full proof-term export is required by the design.

Starting points: Isabelle's `Build.read_theory`, `Export_Theory`, and PIDE entity
markup; PIDE-MCP's definition and state adapters. Reassess cost and scope before
promoting this to the active task list.
