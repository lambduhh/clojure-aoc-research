---
type: Research
title: "Database insights (exploratory)"
description: "How far solvers get, build files, Clojure versions, feature adoption, AI files, editors, other ecosystems' deps, Datomic use. Not pre-registered."
tags: [advent-of-code, results]
created: 2026-11-11
modified: 2026-11-11
source: "analysis/db_insights.clj"
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local]
agents_skills: []
review_maturity: L0 (AI-generated)
review_note: "Script output, not yet human-reviewed. Check the verification doc before quoting."
---

# Database insights (exploratory)

How far solvers get, build files, Clojure versions, feature adoption, AI files, editors, other ecosystems' deps, Datomic use. Not pre-registered.

Produced by: analysis/db_insights.clj.

```text
== Q13 how far solvers get (included solution files; repo-years; 2015-2024 have 25 days)
  2015-2024 (25 days)
   lang      repo-years  median-days  median-last-day  reached-last-day  stopped-by-day-7
   clojure         3936          8.0             10.0             13.9%             40.7%
   elixir          4200          7.0              7.0             10.7%             50.2%
   ocaml           1146          7.0              9.0             15.2%             45.9%
   zig             1464          6.0              7.0             10.2%             51.3%
  2022-2024 only
   lang      repo-years  median-days  median-last-day  reached-last-day  stopped-by-day-7
   clojure         1393            8                9             12.3%             42.0%
   elixir          1798          7.0              8.0             10.1%             49.2%
   ocaml            724          6.5              8.0             12.6%             49.0%
   zig             1113            6                6              8.0%             53.5%
  2025 (12 days)
   lang      repo-years  median-days  median-last-day  reached-last-day  stopped-by-day-7
   clojure          204          7.0              8.0             23.5%             48.5%
   elixir           304          7.0              8.0             18.1%             48.4%
   ocaml            202          7.0              7.0             26.7%             50.5%
   zig              427            6                7             15.5%             53.6%

== Q3 build files in Clojure repos, by creation year (share of cloned Clojure repos)
   year   n   deps.edn  project.clj  bb.edn  any-bb-script  boot  shadow-cljs
   2015   83      8.4%        83.1%    0.0%           0.0%   2.4%         1.2%
   2016  110      2.7%        83.6%    0.0%           0.9%   9.1%         0.0%
   2017  238      7.6%        81.1%    0.4%           0.8%   5.5%         0.0%
   2018  317     17.0%        70.3%    0.0%           0.3%   1.6%         1.3%
   2019  360     23.1%        70.3%    0.3%           1.1%   0.0%         1.1%
   2020  452     32.3%        57.5%    0.9%           1.3%   0.2%         0.9%
   2021  530     40.4%        48.5%    2.1%           4.0%   0.2%         0.4%
   2022  545     44.6%        43.7%    5.0%           7.3%   0.0%         0.7%
   2023  376     44.4%        43.1%    5.3%           6.4%   0.0%         0.0%
   2024  311     48.2%        36.0%    5.1%           6.1%   0.0%         0.0%
   2025  167     53.3%        34.1%    4.8%           6.0%   0.0%         0.6%

== Q18 declared org.clojure/clojure version (main scope), by repo creation year: top minors
   2015 71 repos: ([1.7 48] [1.6 9] [1.9 8] [1.8 7])
   2016 101 repos: ([1.8 63] [1.7 14] [1.9 13] [1.10 7])
   2017 209 repos: ([1.8 133] [1.9 60] [1.10 10] [1.11 5])
   2018 248 repos: ([1.8 123] [1.9 69] [1.10 58] [1.11 7])
   2019 289 repos: ([1.10 229] [1.8 43] [1.9 14] [1.11 11])
   2020 326 repos: ([1.10 295] [1.11 12] [1.12 9] [1.8 8])
   2021 366 repos: ([1.10 300] [1.11 54] [1.12 16] [1.8 9])
   2022 350 repos: ([1.11 222] [1.10 112] [1.12 19] [1.8 4])
   2023 240 repos: ([1.11 195] [1.10 36] [1.12 11] [1.8 2])
   2024 200 repos: ([1.11 98] [1.12 94] [1.10 10] [1.8 1])
   2025 108 repos: ([1.12 67] [1.11 37] [1.10 4] [1.8 1])

== Q6 feature adoption: share of Clojure repos (with solutions in that puzzle year) using each var at least once
   var                            2019   2020   2021   2022   2023   2024   2025
   clojure.core/parse-long        4.3%   4.2%  12.0%  31.7%  43.1%  50.5%  51.2%
   java.lang.Long/parseLong      11.8%  38.2%  26.6%   5.9%  15.3%  15.7%  23.9%
   java.lang.Integer/parseInt    50.4%  69.3%  70.7%  47.7%  54.6%  43.9%  38.0%
   clojure.core/read-string      25.4%  31.9%  30.5%  35.6%  18.6%  16.5%  17.1%
   clojure.edn/read-string        9.6%  11.1%  11.6%  14.1%   6.4%   5.3%   4.9%
   clojure.core/update-vals       1.2%   0.8%   5.5%   6.5%   7.8%  13.0%   2.0%
   clojure.core/abs               4.6%   2.4%   5.7%  34.4%  28.0%  80.1%  48.8%
   clojure.core/partitionv        0.0%   0.2%   0.0%   0.0%   0.0%   1.6%   1.0%
   clojure.math/floor             0.7%   0.0%   0.2%   1.2%   6.8%   4.8%   2.4%
   repos with Clojure solutions per puzzle year: {2019 417, 2020 505, 2021 560, 2022 596, 2023 425, 2024 376, 2025 205}

== Q21 AI tooling files, repos by language (any :repo/ai-files) and by creation year
   by lang: {:clojure 9, :elixir 8, :ocaml 3, :zig 10}
   by creation year: {2020 1, 2022 3, 2023 1, 2024 7, 2025 18}
   by file: {:cursor 4, :agents-md 6, :copilot-instructions 3, :claude-md 15, :cursorrules 3, :claude-dir 3}

== Q22 editor evidence (repos), by language
   clojure ([:intellij 112] [:clj-kondo 99] [:clojure-lsp 51] [:emacs 39] [:vscode 38] [:nrepl-port 28] [:calva 23] [:zed 1])
   elixir ([:vscode 162] [:intellij 24] [:elixir-ls 14] [:zed 1] [:emacs 1])
   ocaml ([:vscode 94] [:emacs 3] [:clj-kondo 1] [:zed 1] [:clojure-lsp 1])
   zig ([:vscode 197] [:intellij 4] [:zed 3] [:vim 2] [:emacs 1])

== AoC helper libraries in other ecosystems: top declared deps (distinct repos, same-ecosystem only, non-local)
   elixir ( 4070 cloned repos): benchee 421, credo 191, mix_test_watch 164, libgraph 139, nimble_parsec 113, httpoison 110, dialyxir 101, jason 101, ssl_verify_fun 90, memoize 86, nx 66, math 55, ex_doc 49, floki 49, req 48
   ocaml ( 1381 cloned repos): dune 697, ocaml 667, odoc 616, core 349, str 322, base 226, stdio 163, re 162, angstrom 103, unix 73, fmt 59, alcotest 53, containers 52, core_unix.command_unix 51, cmdliner 44
   zig ( 1861 cloned repos): regex 17, mvzr 15, clap 8, zbench 7, ziglangSet 7, mecha 5, raylib_zig 3, libadvent 2, qpEngine 2, raylib-zig 2, vaxis 2, zul 2, AOC2024 1, AOC2025 1, Musubi 1

== Datomic in AoC repos
   [[com.datomic/datomic-free 3] [com.datomic/peer 1]]
```
