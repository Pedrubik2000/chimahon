# Jiten port: notice

The Kotlin files in this folder (and `kumahon-core/src/main/resources/jiten/deconjugator.json`) are ported from
**Jiten** (https://github.com/Sirush/Jiten, commit `44b48a4`), Copyright (c) the Jiten authors, licensed under the
**Apache License, Version 2.0** (http://www.apache.org/licenses/LICENSE-2.0). Each ported file names its original
in its header.

`kumahon-core/src/main/resources/jiten/char.def`, `unk.def` and `rewrite.def` are Jiten's Sudachi resources
(`Shared/resources`, themselves Sudachi's, Apache-2.0); `char.def` has one line changed (`NOOOVBOW2`, which only
sudachi.rs reads, became `NOOOVBOW`).

The files were **modified**: translated from C# to Kotlin, Jiten's Postgres tables replaced by `JitenLexicon` (built by
kumapie's `tools/kumapie/jiten_lexicon.py` (kumapie-server) from JMdict/JMnedict, EDRDG, CC BY-SA 4.0, and Jiten's frequency list), the
WanaKana-net calls replaced by a small `WanaKana` object, caches and diagnostics simplified, Jiten's ruby reading
priors, Redis word cache and deck statistics left out. They are distributed as
part of kumahon under the GPL-3.0, as the Apache-2.0 allows.

`deconjugator.json` comes to Jiten from JL (https://github.com/rampaa/JL, `JL.Core/Resources/deconjugation_rules.json`;
Apache-2.0), copied unchanged from Jiten.
