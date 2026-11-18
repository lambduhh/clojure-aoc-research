---
type: Research
title: "Question 11: lines of code, Clojure vs Elixir, OCaml and Zig (provisional)"
description: "The pre-registered comparison of cloc code lines per puzzle, with robustness checks and the equal-size minimum. Provisional until the mapper's AI labels are spot-checked."
tags: [advent-of-code, results]
created: 2026-11-17
modified: 2026-11-17
source: "analysis/q11.clj"
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: unrecorded
tools: [babashka, clojure, Datomic Local]
agents_skills: []
review_maturity: L0 (AI-generated)
review_note: "Script output, not yet human-reviewed."
---

# Question 11: lines of code, Clojure vs Elixir, OCaml and Zig (provisional)

The pre-registered comparison of cloc code lines per puzzle, with robustness checks and the equal-size minimum. Provisional until the mapper's AI labels are spot-checked.

Produced by: analysis/q11.clj.

```text
== Funnel: solutions ([repo puzzle] pairs) by language
   lang      solutions   livemd  uncounted-other  zero-lines  over-2000      kept
   clojure       42367        0               24           1         43     42299
   elixir        39930     1257               60          16         57     38540
   ocaml         12812        0               17           5         12     12778
   zig           15564        0               15           3         22     15524
   cloc 2.10, args --by-file --json --quiet --skip-uniqueness --force-lang=Clojure,bb; 126601 of 128002 included files counted

== Q11 primary: median cloc code lines per puzzle, r = median_clojure / median_L
   Clojure vs elixir        puzzles 262  geomean r 0.776 [95% CI 0.765-0.788]  r<1  98.1%  Wilcoxon z -13.94 p < 1e-6 (significantly different)  d -2.03 (large)  H1 supported
                            (3 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs ocaml         puzzles 249  geomean r 0.775 [95% CI 0.759-0.792]  r<1  92.8%  Wilcoxon z -13.12 p < 1e-6 (significantly different)  d -1.54 (large)  H1 supported
                            (4 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs zig           puzzles 174  geomean r 0.467 [95% CI 0.453-0.482]  r<1 100.0%  Wilcoxon z -11.44 p < 1e-6 (significantly different)  d -3.73 (large)  H1 supported

== Robustness 1: pipeline code lines (:file/code-lines), same solutions
   Clojure vs elixir        puzzles 262  geomean r 0.762 [95% CI 0.751-0.773]  r<1  99.2%  Wilcoxon z -14.00 p < 1e-6 (significantly different)  d -2.20 (large)  H1 supported
                            (1 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs ocaml         puzzles 249  geomean r 0.774 [95% CI 0.758-0.789]  r<1  94.4%  Wilcoxon z -13.23 p < 1e-6 (significantly different)  d -1.62 (large)  H1 supported
                            (1 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs zig           puzzles 174  geomean r 0.487 [95% CI 0.472-0.502]  r<1 100.0%  Wilcoxon z -11.44 p < 1e-6 (significantly different)  d -3.54 (large)  H1 supported

== Robustness 2: Clojure without top-level (comment ...) forms; L keeps the pipeline count
   Clojure vs elixir        puzzles 262  geomean r 0.737 [95% CI 0.727-0.748]  r<1 100.0%  Wilcoxon z -14.03 p < 1e-6 (significantly different)  d -2.51 (large)  H1 supported
   Clojure vs ocaml         puzzles 249  geomean r 0.748 [95% CI 0.733-0.764]  r<1  96.0%  Wilcoxon z -13.36 p < 1e-6 (significantly different)  d -1.82 (large)  H1 supported
                            (1 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs zig           puzzles 174  geomean r 0.469 [95% CI 0.455-0.483]  r<1 100.0%  Wilcoxon z -11.44 p < 1e-6 (significantly different)  d -3.78 (large)  H1 supported

== Robustness 3 (tokens) and 4 (load check): not run; see amendment 4

== Also reported: equal-size minimum (10 per language, 1,000 draws, seed 20261118), cloc
   Clojure vs elixir (min)  puzzles 262  geomean r 0.761 [95% CI 0.732-0.794]  r<1  90.8%  Wilcoxon z -12.27 p < 1e-6 (significantly different)  d -0.80 (large)  H1 supported
                            (5 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs ocaml (min)   puzzles 249  geomean r 0.853 [95% CI 0.808-0.905]  r<1  78.7%  Wilcoxon z  -8.15 p < 1e-6 (significantly different)  d -0.34 (medium)  H1 supported
                            (5 puzzles with r = 1 dropped from Wilcoxon)
   Clojure vs zig (min)     puzzles 174  geomean r 0.489 [95% CI 0.465-0.517]  r<1  96.6%  Wilcoxon z -10.94 p < 1e-6 (significantly different)  d -2.02 (large)  H1 supported

== Distribution of log r, primary measure (negative = Clojure shorter)
   Clojure vs elixir
      -1.0.. -0.8    1 
      -0.8.. -0.6    3 #
      -0.6.. -0.4   23 ######
      -0.4.. -0.2  150 ########################################
      -0.2..  0.0   80 #####################
       0.0..  0.2    5 #
   Clojure vs ocaml
      -1.0.. -0.8    1 
      -0.8.. -0.6    3 #
      -0.6.. -0.4   40 #############
      -0.4.. -0.2  119 ########################################
      -0.2..  0.0   68 #######################
       0.0..  0.2   17 ######
       0.2..  0.4    0 
       0.4..  0.6    1 
   Clojure vs zig
      -1.8.. -1.6    1 #
      -1.6.. -1.4    1 #
      -1.4.. -1.2    2 #
      -1.2.. -1.0   14 ########
      -1.0.. -0.8   53 ################################
      -0.8.. -0.6   67 ########################################
      -0.6.. -0.4   30 ##################
      -0.4.. -0.2    6 ####

== Known biases: published solutions only; solver populations differ (OCaml and Zig likely enthusiasts); 
   golfed and (since 2022) possibly AI-written solutions; Zig least like-for-like; 10 MB clone cap skips 
   more OCaml and Zig repos; Livebook solutions dropped (Elixir). Mapper precision 95.8% on the holdout; 
   AI labels behind it still pending a human spot-check.
```
