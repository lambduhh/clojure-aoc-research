---
type: Pre-registration
title: "Pre-registration: lines of code across languages (questions 11-13)"
description: "Analysis plan for the cross-language lines-of-code comparison, committed before any comparison was computed, with dated amendments."
tags: [advent-of-code]
created: 2026-11-10
modified: 2026-11-11
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local, gh]
agents_skills: []
review_maturity: L1 (Human-directed)
review_note: "Decisions are Jordan's; Claude drafted the text. Not yet read end to end by a human."
---

# Pre-registration: lines of code across languages (questions 11–13)

This file is committed **before** any cross-language line-count comparison is computed, so the git history shows the analysis plan came first. Changes made after the first comparison runs are listed under [Deviations](#deviations), with dates and reasons.

**Status at commit (2026-11-10):**
- Cloning is in progress.
- The extract stage has computed line counts per file, as part of building the dataset.
- No comparison between languages has been computed or looked at.
- Separately, a dependency ranking was looked at for Clojure repos only, to check the parser.

## Data

- **Population:** AoC-named repos created 2015–2025 whose primary language is Clojure, Elixir, OCaml or Zig. They come from [programming-language-vitality](https://github.com/thiagooak/programming-language-vitality/tree/916e0f55bd44afe55382c9925f05cf3e243abfc8) at commit `916e0f5` ([`data/repos.edn`](../../data/repos.edn)).
- **Unit:** a **solution**, meaning one repo's solution files for one puzzle `[year day]`, added together. Part 1 and part 2 in separate files count as one solution.
- **Inclusion:**
  - `:file/role :solution`, which excludes `:config`;
  - not a template (`:file/template?` false; see amendment 1);
  - a mapped puzzle;
  - the file language matches the repo's dataset language.
- **Exclusion:** solutions over 2,000 code lines, which are likely generated or copied in. The number excluded is reported for each language.

## Measures

- **Primary: code lines.** Non-blank, non-comment lines as counted by [`cloc`](https://github.com/AlDanial/cloc). This follows Nanz & Furia, [A Comparative Study of Programming Languages in Rosetta Code](https://arxiv.org/abs/1409.0252) (ICSE 2015), which counted "the non-blank non-comment lines of code" with `cloc`.
- **Robustness checks.** All are reported alongside the primary result. None replaces it.
  1. The pipeline's own count (`:file/code-lines`).
  2. For Clojure, the same count without top-level `(comment …)` forms (`:file/code-lines-no-comment-forms`). These forms are REPL scratch work.
  3. Tokens, so formatting style can't drive the result. The tokenizer for each language is specified in a deviation entry before it's used.
  4. Only solutions whose repo passes phase 2's load check (code rot), following Nanz & Furia's "compiles" filter.

## Question 11: is Clojure shorter?

For each comparison language L (Elixir, OCaml and Zig, each tested separately):

- **Eligible puzzles:** at least 10 solutions in **both** Clojure and L.
- **For each puzzle:** take the median code lines for Clojure and for L, and form `r = median_clojure / median_L`.
- **Across puzzles:**
  - the geometric mean of `r`, i.e. `exp(mean(log r))`, with a 95% bootstrap confidence interval that resamples puzzles (10,000 resamples, fixed seed);
  - the share of eligible puzzles with `r < 1`;
  - the distribution of `log r`, shown as a chart.
- **Also reported:** the same analysis using the **minimum** per language instead of the median, computed on equal-size subsamples (see amendment 2). The minimum is "the shortest solution anyone wrote", close to what the language makes possible. The median is "what solvers typically write". The median is primary.
- **H1 (Clojure vs L):** Clojure solutions are shorter.
  - **Test:** Wilcoxon signed-rank on `log r` across eligible puzzles, two-sided.
  - **Effect size:** Cohen's d on `log r`.
  - **Reporting bands**, following Nanz & Furia so the results can be compared:
    - p < 0.01: "significantly different";
    - 0.01 ≤ p < 0.05: "tends to be different";
    - otherwise "no evidence of a difference".
    - Effect size: vanishing (< 0.05), small (< 0.3), medium (< 0.7), large (≥ 0.7).
  - **H1 is supported for L** if the geometric-mean ratio is below 1 **and** p < 0.05.
  - **The result is reported whatever it is,** including if it doesn't favour Clojure.

## Question 12: the AI era (exploratory)

- **What's compared:** the same per-puzzle ratios for puzzles from 2015–2021 against 2022–2025, plus each language's median code lines by puzzle year.
- **No hypothesis test is used to make a claim.** Puzzle difficulty and solver populations change from year to year too, so any difference is described as an observation, not a cause.

## Question 13: how far solvers get (descriptive)

- **For each repo and puzzle year:** days present, last day present, and gaps.
- **Shown per language:** the share of repo-years that reach day *d*, for each *d*.
- **No hypothesis.**

## Known biases (stated wherever results appear)

- Only people who publish their solutions are counted.
- Solver populations differ by language. OCaml and Zig users are likely self-selected enthusiasts.
- Some solutions are golfed for brevity.
- Since 2022, some solutions may be AI-written.
- Zig is a systems language and verbose by design, so Clojure vs Zig is the least like-for-like comparison. Elixir, being dynamic and functional, is the closest peer.
- **Clone funnel** (added 2026-11-10, after cloning and before any comparison). The 10 MB size cap skipped a larger share of OCaml and Zig repos than Clojure or Elixir repos:

  | Language | Cap-skipped | Of listed | Missing | Failed | Cloned |
  |---|---|---|---|---|---|
  | Clojure | 12 | 3,512 (0.3%) | 10 | 1 | 3,489 |
  | Elixir | 14 | 4,094 (0.3%) | 10 | 0 | 4,070 |
  | OCaml | 41 | 1,426 (2.9%) | 4 | 0 | 1,381 |
  | Zig | 55 | 1,928 (2.9%) | 12 | 0 | 1,861 |

  If large repos differ systematically (e.g. monorepos covering several years), this biases OCaml and Zig more. Every analysed repo is under the cap, so the comparison is like-for-like for repos of that size. Who the cap excludes is reported as a caveat; there is no extra analysis.
- Puzzle mapping depends on file paths. Mapping precision is measured on a hand-labelled sample before results are trusted, with a threshold of 95%.

## Deviations

### Amendment 1 (2026-11-10, before any comparison)

The first mapper-precision sample (seed 20261110, 160 files, two blind labellers) measured **84.2%** precision on mapped files (101/120). That's below the 95% gate, and it varied by language:

| Language | Correct |
|---|---|
| Clojure | 28/30 |
| Elixir | 29/30 |
| OCaml | 25/30 |
| Zig | 19/30 |

The causes, each fixed with a test:

1. A two-digit-year pattern read `day15` as `y15` (2015), so every day 15–25 got the wrong year, or none.
2. Build and config files (`build.zig`, `mix.exs`, `.formatter.exs`) were classed as solutions. They now get role `:config`.
3. Unfilled templates and stubs counted as solutions. Files whose digit-normalised content is shared across 2 or more puzzles in a repo are now `:file/template? true` and are **excluded** from questions 11–13. Templates are more common in Zig and Elixir repos, so leaving them in would have biased those comparisons.
4. Missed day patterns (`5a`, `01_2`, `23.1`, `Day 5`, `10-factory`, `Q22`, `ex1`).
5. Four-digit years now outrank two-digit years.

The fixes were tuned on that sample, so precision is **re-measured on a fresh holdout sample** with a different seed before any comparison runs. Both measurements are reported.

### Amendment 3 (2026-11-10, before any comparison): holdout result

**The holdout sample:** seed 20261111, 200 files, no overlap with the first sample. Per language it has three groups: 30 included files, 10 unmapped and 10 template-flagged. It was labelled by two blind AI labellers using **different methods** (path-first and content-first), who disagreed on 9 files; an adjudicator settled all 9.

**Results before any further change:**

| Measure | Result |
|---|---|
| **Included-file precision** (a real solution and the right puzzle) | **115/120 = 95.8%** (95% CI 90.6–98.2). Passes the 95% gate on the point estimate. |
| Clojure | 30/30 |
| Elixir | 29/30 |
| OCaml | 29/30 |
| Zig | 27/30 (90%, CI 74.4–96.5) |
| Puzzle key correct, ignoring whether the file is a stub | 118/120 |
| **Template detector precision** | **37/40 = 92.5%** (CI 80.1–97.4). Two real Clojure solutions were wrongly flagged (thin intcode wrappers repeated across days). |
| **Unmapped files that are real solutions** | **12/40.** By language: Clojure 3/10, Elixir 6/10, OCaml 1/10, Zig 2/10. Estimated recall of included files, from these small samples: Clojure ≈95%, Elixir ≈90%, OCaml ≈97.5%, Zig ≈95.5%. Elixir's misses are mostly modules named after puzzle titles (`no_space_left_on_device`, `dive.ex`), which need a title list (design section 5). |

**Change after the holdout.** The holdout can't validate it.
- `d11q2` was read as day 2; it is day 11, question 2.
- `advent4`, `aoc04` and `advent-10` are now read as days, for numbers 1–14 only, which can't be years.
- `11-01` is read as day 11, part 1.

Tests pin each case, and they were confirmed to fail on the previous mapper.

**Residual biases:**
- Unfinished but mapped solutions (2 of 3 Zig errors) can't be detected from paths. They add noise to line counts.
- Lower Elixir recall shrinks the Elixir sample. That biases the comparison only if missed solutions differ in length from found ones, which hasn't been tested.
- AI labels are pending Jordan's spot-check (`data/labels/holdout-labels.edn`).

### Amendment 2 (2026-11-10, before any comparison)

An independent code review (four reviewers, each checked by a skeptic) confirmed several defects that bias the measures by language. The fixes are listed in the repo's commit history. Two changes concern this plan directly:

1. **The minimum is biased by sample size.** The minimum of *n* solutions shrinks as *n* grows, so a language with more solutions per puzzle gets a shorter minimum by chance alone. Stubs dominate it too. The minimum is therefore computed on **equal-size subsamples**: for each puzzle, draw 10 solutions per language, take each language's minimum, repeat 1,000 times with a fixed seed, and use the median of those minima. Templates and stubs are already excluded (amendment 1).
2. **The primary measure isn't computed yet.** `cloc` isn't installed on the analysis machine. Until it is, only the robustness counts exist. No comparison will be reported as primary until `cloc` runs.

The review also made the pipeline's own count comparable across languages:
- inline tests (Zig `test` blocks, OCaml `let%test`) are excluded, as separate test files already were for Clojure and Elixir;
- documentation is excluded in every language;
- string-aware comment stripping for OCaml;
- symlink placeholders are excluded.
