---
type: Research
title: "AoC repo trends and follow-on rates (exploratory)"
description: "AoC-named repos per year by language, and the follow-on rate for all owners and for newcomers. Not pre-registered."
tags: [advent-of-code, results]
created: 2026-11-11
modified: 2026-11-11
source: "analysis/dataset_insights.bb and analysis/newcomers.bb"
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local]
agents_skills: []
review_maturity: L0 (AI-generated)
review_note: "Script output, not yet human-reviewed. Check the verification doc before quoting."
---

# AoC repo trends and follow-on rates (exploratory)

AoC-named repos per year by language, and the follow-on rate for all owners and for newcomers. Not pre-registered.

Produced by: analysis/dataset_insights.bb and analysis/newcomers.bb.

```text
== I1: AoC-named repos created per year, by language (dataset, deduped)
year  clojure   elixir    ocaml      zig   clojure-share
2015      83       64        6        0     54.2%
2016     110      124        9        2     44.9%
2017     240      228       40        5     46.8%
2018     318      382       55        7     41.7%
2019     364      351       67       21     45.3%
2020     456      393       81       55     46.3%
2021     534      554      108      152     39.6%
2022     549      542      150      161     39.2%
2023     377      572      373      415     21.7%
2024     312      580      268      580     17.9%
2025     169      304      269      530     13.3%

== T2 replicated (Thiago's method): owners with an AoC-named repo created Nov 15-Jan 31,
   who created a NON-AoC repo in the same language within the following 12 months (Feb 1 .. Jan 31)
   season            clojure            elixir             ocaml               zig
   2015      31/88     35.2%    30/73     41.1%     0/5       0.0%     0/0       0.0%
   2016      30/84     35.7%    39/96     40.6%     1/8      12.5%     0/2       0.0%
   2017      69/211    32.7%    66/204    32.4%    13/41     31.7%     0/5       0.0%
   2018      73/282    25.9%   132/362    36.5%    11/51     21.6%     3/7      42.9%
   2019      83/322    25.8%   100/316    31.6%     9/60     15.0%     7/23     30.4%
   2020     114/424    26.9%    97/341    28.4%    16/76     21.1%    17/52     32.7%
   2021     101/449    22.5%   124/487    25.5%    16/100    16.0%    26/139    18.7%
   2022      88/465    18.9%   101/474    21.3%    28/128    21.9%    42/140    30.0%
   2023      56/303    18.5%   105/496    21.2%    37/289    12.8%    84/334    25.1%
   2024      41/273    15.0%    90/493    18.3%    35/230    15.2%   124/490    25.3%
   (exploratory; not pre-registered. 'Non-AoC repo' = name doesn't match research.names/aoc-repo?; only repos whose PRIMARY language is that language are visible)
== Newcomers only: owners whose FIRST repo in the language is an AoC-named repo created Nov 15 - Jan 31,
   who create a non-AoC repo in that language in the following 12 months
   season            clojure            elixir             ocaml               zig
   2015       6/40     15.0%     9/37     24.3%     0/5       0.0%     0/0       0.0%
   2016       3/31      9.7%    15/44     34.1%     1/4      25.0%     0/2       0.0%
   2017      17/81     21.0%    14/81     17.3%     5/25     20.0%     0/5       0.0%
   2018      12/101    11.9%    21/124    16.9%     6/34     17.6%     1/5      20.0%
   2019      14/127    11.0%    12/116    10.3%     1/32      3.1%     1/15      6.7%
   2020      31/178    17.4%    24/140    17.1%     2/44      4.5%     5/35     14.3%
   2021      22/162    13.6%    31/221    14.0%     3/65      4.6%    11/106    10.4%
   2022      10/166     6.0%    26/216    12.0%    10/68     14.7%    19/93     20.4%
   2023      12/106    11.3%    26/252    10.3%    12/190     6.3%    41/230    17.8%
   2024       6/115     5.2%    29/267    10.9%    20/165    12.1%    51/334    15.3%
   (exploratory. 'First repo' = earliest created repo whose PRIMARY language is that language in Thiago's dataset)
```
