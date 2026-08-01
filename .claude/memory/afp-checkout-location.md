---
name: afp-checkout-location
description: AFP checkout is at ~/repos/afp (Isabelle2025-2); thys/ has ~962 entries
metadata: 
  node_type: memory
  type: reference
  originSessionId: be018111-a108-4534-ad37-80cff9440ff8
---

The user's AFP working copy is at `~/repos/afp`, checked out at the version for
**Isabelle2025-2**. Entries live under `~/repos/afp/thys/<Entry>/` (~962 entries;
159 are ≥10k total .thy lines). This is the deployment corpus for isabelle-query's
width census and the sample for `_corpus_constants.py`.

See [[verify-afp-size-assumptions]].
Reading it from Bash: use simple commands (compound `git -C ... && find | xargs`
tripped the permission gate; single statements over `~/repos/afp` work).
