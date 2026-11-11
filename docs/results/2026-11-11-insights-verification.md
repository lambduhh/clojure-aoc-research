---
type: Review
title: "Verification of the first-pass insights (2026-11-11)"
description: "Three independent checks per claim (recompute, methodology, critic), with corrected wording for each claim."
tags: [advent-of-code]
created: 2026-11-11
modified: 2026-11-11
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local, gh]
agents_skills: []
review_maturity: L0 (AI-generated)
review_note: "AI verification of AI analysis. Not yet human-reviewed."
---

# Verification of the first-pass insights (2026-11-11)

Three independent checks were run on each claim:
- **recompute:** the number derived again from the extract and dataset files with babashka, without touching Datomic;
- **methodology:** confounds and alternative explanations;
- **critic:** what's missing or overstated.

Every number reproduced except C12. The **wording** needs correcting in several places.

| # | Claim | Verdict | Say it this way |
|---|---|---|---|
| C1 | Clojure's share of AoC-named repos fell after 2022 | Confirmed, with caveat | Among these four languages only, Clojure's share was 39–54% in 2015–2022 and 13–22% from 2023. Part of the fall is OCaml and Zig growing. Counted by repos created, not by participants. |
| C2 | Clojure AoC repos peaked at 549 in 2022 | Confirmed, with caveat | Counts of repos **created**. 34% of Clojure repo-years come from repos reused across years, so this can't yet distinguish fewer solvers from people reusing one repo (needs commit timestamps). OCaml's 2023 jump was a one-year spike. |
| C3 | Follow-on rate falling | Confirmed, with caveat | All-owner follow-on fell from about 35% (2015–16) to about 15–21% (2024); the figure depends on the window. Clojure's pooled rate is below Elixir's (22.1% vs 25.2%), but individual seasons since 2020 aren't separable at 95%. |
| C4 | Newcomer follow-on vs Elixir | Fragile | Don't present it as a contrast. Clojure was above Elixir in 2023, each year rests on 6–12 owners, and 2024 comes out at 5.2–13% depending on the window. |
| C5 | AoC newcomers to Clojure fell | Confirmed, with caveat | A step change in 2023 (166 → 106 → 115). "Newcomer" means the first public repo whose primary language is Clojure. |
| C6 | How far solvers get | Partly | Only the Clojure-vs-Elixir gap survives when split by year (median last day 10 vs 7). Drop the OCaml and Zig comparisons, which reflect year mix. |
| C7 | parse-long adoption | Confirmed, with caveat | 32% → 51% since 2022, when it became available. `Integer/parseInt` is still in about 38%. These are var references, by puzzle year. |
| C8 | deps.edn vs project.clj | Confirmed, with caveat | Level in 2022–23; deps.edn leads from 2024. |
| C9 | Declared Clojure version | Corrected | 1.12 is the most common in 2025: **66** of 108 repos, not 67. |
| C10 | AI config files | Confirmed, with caveat | At least 38 repos (files anywhere in the tree). A lower bound on committed config, not on AI use. |
| C11 | Elixir/OCaml common deps | Confirmed, with caveat | 250 of the 421 Elixir repos declaring benchee use the mhanberg starter layout, so these are template defaults, not deliberate choices. |
| C12 | Datomic in AoC repos | **Wrong** | **2** repos (datomic-free 1, peer 1). The other 2 were "adventure" apps; fixed in PR #3. |
| C13 | "Matches Thiago's ~20%" | Unverifiable | His **method** is in his PR #15 comment; the **number** has no written source. Rates of 18.5–22.5% for 2021–2023 are consistent with one reading. Confirm his window and filters with him. |

**The most valuable next check** (critic): count distinct owners committing to day-mapped files between 1 Dec and 31 Jan, by commit timestamp, using a blobless re-fetch that reads commit metadata only and runs no code. It would settle C2 and remove the backfill confound from C6–C9.
