# clojure-aoc-research

What Clojure Advent of Code solvers actually use, compared with solvers in Elixir, OCaml and Zig. It covers dependencies, var references, line counts, how far people get, and whether old repos still work. Built with Clojure, babashka and Datomic.

Repo lists come from Thiago Carvalho's [programming-language-vitality](https://github.com/thiagooak/programming-language-vitality/tree/916e0f55bd44afe55382c9925f05cf3e243abfc8) datasets, pinned to commit `916e0f5`. No Advent of Code puzzle text or inputs are stored in this repo.

## Stages

| Stage | Command | Output |
|---|---|---|
| 1. Collect | `bb collect` | `data/repos.edn`: AoC-named repos created 2015–2025, deduplicated; `data/datasets.edn`: dataset URLs and SHA-256 hashes |
| 2. Clone | `bb clone` | Shallow clones in the cache, plus `clone-log.edn` there (one EDN map per attempt). Resumable |
| 3. Extract | `bb extract` | Per repo, in the cache. For each file: language, role, puzzle (year and day), whether it's a template, and line counts (code, docs, inline tests). For the repo: build files, AI files, editor signals, Java version, declared deps |
| 3b. Var references | `clojure -M:kondo` | Clojure only, via clj-kondo's static analysis: required namespaces and references to each var per file. Special forms, refers, imports and `(comment)` forms are excluded |
| 3c. Core var versions | `clojure -M:core-vars` | `data/core-vars.edn`: when each core var was added |
| 4. Load | `clojure -M:load --fresh` | Datomic Local on disk, one transaction per repo, each pointing at its run. `--fresh` rebuilds; loading into a populated database is refused |
| 4b. cloc | `clojure -M:cloc` | Question 11's primary measure: `cloc` code lines for every included solution file, in `<cache>/cloc/counts.edn` (prereg amendment 4). Needs `cloc` on `$PATH` |
| 5. Query | `research.queries` | Datalog, one query per research question |

Run everything after cloning with `bb after-clone "<note>"`. Tests: `bb test` (babashka) and `bb test-jvm` (clj-kondo stage and loader).

**Checks before results are trusted:** the puzzle mapper's precision is measured on hand-labelled samples ([`data/labels/`](data/labels)), and the line-count comparison is pre-registered in [`docs/prereg/lines-of-code.md`](docs/prereg/lines-of-code.md).

`bb status` summarises the clone log. `bb test` runs the tests. `bb tasks` lists all tasks.

## The cache

Datasets, clones and Datomic storage live outside the repo, in `~/aoc-research-cache`. Override it with `AOC_RESEARCH_CACHE`. Clones are named by GitHub's numeric repo id.

## How cloning treats untrusted repos

Clones are shallow HTTPS clones with:
- no credential helpers;
- no git hooks, including your global ones;
- symlinks checked out as plain files;
- Git LFS downloads skipped;
- a 3-minute timeout each.

Each clone goes into a temporary folder first and moves into place only if it succeeds. Repos over 10 MB (GitHub's `size` field) are skipped and logged as `:skipped-size`.

## Reproducing this on a smaller machine

The full run covers 10,960 repos: 3,512 Clojure, 4,094 Elixir, 1,426 OCaml and 1,928 Zig. That's several GB of clones.

**If you're reproducing the method, a stratified sample is a reasonable start**, e.g. a fixed number of repos per language and creation year, with a fixed random seed. Be aware of the trade-offs:
- Rarely used libraries may not appear at all. A library used by 2% of repos has about a 30% chance of being absent from a sample of 60.
- Overall rankings need reweighting by each stratum's share of the population.
- Later puzzle days, which fewer people solve, get thin coverage.

## Which repos count as AoC repos

A repo counts if its *name* contains:
- "advent";
- "aoc" at a word boundary;
- or a camelCase "Aoc"/"AoC".

This rejects names where "aoc" sits inside a word, such as `kaocha` and `metaocaml`. See [`research.names`](src/research/names.clj) and its tests.

## License

MIT
