---
type: Research
title: "Dependency queries, run 3 (provisional)"
description: "Funnel, declared deps, required namespaces and template coverage on the run-3 database, after the holdout fixes."
tags: [advent-of-code, results]
created: 2026-11-10
modified: 2026-11-11
source: "src/research/queries.clj"
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local]
agents_skills: []
review_maturity: L0 (AI-generated)
review_note: "Script output, not yet human-reviewed. Check the verification doc before quoting."
---

# Dependency queries, run 3 (provisional)

Funnel, declared deps, required namespaces and template coverage on the run-3 database, after the holdout fixes.

Produced by: src/research/queries.clj.

```text
== Q15 funnel
   [:clojure :cloned 3489]
   [:clojure :failed 1]
   [:clojure :missing 10]
   [:clojure :skipped-size 12]
   [:elixir :cloned 4070]
   [:elixir :missing 10]
   [:elixir :skipped-size 14]
   [:ocaml :cloned 1381]
   [:ocaml :missing 4]
   [:ocaml :skipped-size 41]
   [:zig :cloned 1861]
   [:zig :missing 12]
   [:zig :skipped-size 55]
   included solution files [:clojure 45766]
   included solution files [:elixir 47888]
   included solution files [:ocaml 15443]
   included solution files [:zig 18905]

== Q1 Clojure repos (cloned): 3489 | with any declaration: 2880
   2519  87.5%  org.clojure/clojure
    712  24.7%  org.clojure/math.combinatorics
    259   9.0%  org.clojure/math.numeric-tower
    257   8.9%  org.clojure/data.priority-map
    222   7.7%  io.github.cognitect-labs/test-runner
    201   7.0%  org.clojure/core.match
    195   6.8%  org.clojure/test.check
    165   5.7%  org.clojure/core.async
    164   5.7%  instaparse/instaparse
    148   5.1%  nrepl/nrepl
    147   5.1%  net.mikera/core.matrix
    128   4.4%  criterium/criterium
    126   4.4%  cider/cider-nrepl
    105   3.6%  org.clj-commons/digest
     93   3.2%  clj-http/clj-http
     85   3.0%  org.clojure/data.json
     80   2.8%  lambdaisland/kaocha
     74   2.6%  org.clojure/core.logic
     73   2.5%  aysylu/loom
     70   2.4%  org.clojure/tools.namespace
     61   2.1%  org.clojure/tools.trace
     59   2.0%  com.taoensso/tufte
     57   2.0%  quil/quil
     56   1.9%  com.rpl/specter
     56   1.9%  ubergraph/ubergraph
     54   1.9%  io.github.seancorfield/build-clj
     51   1.8%  io.github.nextjournal/clerk
     50   1.7%  com.clojure-goes-fast/clj-async-profiler
     50   1.7%  dev.weavejester/medley
     45   1.6%  io.github.clojure/tools.build
     41   1.4%  org.clojure/tools.cli
     39   1.4%  cheshire/cheshire
     39   1.4%  lein-cljfmt/lein-cljfmt
     37   1.3%  net.mikera/vectorz-clj
     37   1.3%  org.clojure/clojurescript
     36   1.3%  clojure.java-time/clojure.java-time
     35   1.2%  com.taoensso/timbre
     34   1.2%  metosin/malli
     34   1.2%  org.babashka/cli
     31   1.1%  net.cgrand/xforms

== Q2 required namespaces (distinct Clojure repos), top 30
   2937  clojure.string
   1920  clojure.test
   1827  clojure.set
   1418  clojure.java.io
    668  clojure.math.combinatorics
    376  clojure.pprint
    346  clojure.edn
    304  clojure.math
    254  clojure.data.priority-map
    219  clojure.walk
    214  clojure.math.numeric-tower
    183  clojure.spec.alpha
    163  clojure.core.match
    142  advent-of-code.core
    137  instaparse.core
    126  clojure.core.matrix
    126  clojure.zip
    115  clojure.core.async
     89  aoc.core
     88  digest
     85  clojure.data
     78  clojure.data.json
     78  criterium.core
     73  clj-http.client
     72  clojure.core.reducers
     71  advent.core
     66  utils
     60  clojure.repl
     58  babashka.fs
     55  loom.graph

== Q4 template coverage (lambduhh/clojure-template-aoc deps.edn)
     85  org.clojure/data.json
   2519  org.clojure/clojure
      0  com.datomic/local
    259  org.clojure/math.numeric-tower
     30  org.clojure/data.csv
    712  org.clojure/math.combinatorics
    201  org.clojure/core.match
     50  dev.weavejester/medley
    257  org.clojure/data.priority-map
    222  io.github.cognitect-labs/test-runner
    195  org.clojure/test.check
    148  nrepl/nrepl
     25  djblue/portal
    128  criterium/criterium
     51  io.github.nextjournal/clerk
     12  clj-kondo/clj-kondo
```
