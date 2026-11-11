<!-- DRAFT: AI-drafted from decisions made with Jordan Miller on 2026-11-10; pending Jordan's review. -->

# Pre-registration: lines of code across languages (questions 11–13)

This file is committed **before** any cross-language line-count comparison is computed, so the git history shows the analysis plan came first. Changes made after the first comparison runs are listed under [Deviations](#deviations), with dates and reasons.

**Status at commit (2026-11-10):**
- Cloning is in progress.
- The extract stage has computed line counts per file, as part of building the dataset.
- No comparison between languages has been computed or looked at.
- Separately, a dependency ranking was looked at for Clojure repos only, to check the parser.

## Data

- **Population:** AoC-named repos created 2015–2025 whose primary language is Clojure, Elixir, OCaml or Zig. They come from [programming-language-vitality](https://github.com/thiagooak/programming-language-vitality/tree/916e0f55bd44afe55382c9925f05cf3e243abfc8) at commit `916e0f5` ([`data/repos.edn`](../data/repos.edn)).
- **Unit:** a **solution**, meaning one repo's solution files for one puzzle `[year day]`, added together. Part 1 and part 2 in separate files count as one solution.
- **Inclusion:**
  - `:file/role :solution`;
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
- **Also reported:** the same analysis using the **minimum** per language instead of the median. The minimum is "the shortest solution anyone wrote", close to what the language makes possible. The median is "what solvers typically write". The median is primary.
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

  If large repos differ systematically (e.g. monorepos covering several years), this biases OCaml and Zig more. A robustness check repeats question 11 on repos under 10 MB in every language, which is the same population, so it is reported as a caveat rather than an extra analysis.
- Puzzle mapping depends on file paths. Mapping precision is measured on a hand-labelled sample before results are trusted, with a threshold of 95%.

## Deviations

*None yet.*
