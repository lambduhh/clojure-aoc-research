---
type: Reference
title: "Exploratory insight scripts"
description: "What each script under analysis/ computes, how to run it, and where its output goes. None is pre-registered."
tags: [advent-of-code]
created: 2026-11-11
modified: 2026-11-17
ai_assisted: "Claude Opus 5.5 via Claude Code"
session: 0a61cb0b
tools: [babashka, clojure, Datomic Local, gh]
agents_skills: []
review_maturity: L2 (Human-reviewed)
review_note: "Viewed by lambduhh in PR #4 on 2026-11-11."
reviewed_at: 2026-11-11
---

# analysis/

Exploratory insight scripts. **None of these is pre-registered.** Treat them as descriptive, and check them before citing.

| Script | Run | Output |
|---|---|---|
| `dataset_insights.bb` | `bb -cp src analysis/dataset_insights.bb` | AoC-named repos per year by language, and Thiago's follow-on method (all owners) |
| `newcomers.bb` | `bb -cp src analysis/newcomers.bb` | The same follow-on method, for newcomers only |
| `db_insights.clj` | `clojure -M -i analysis/db_insights.clj` | From the Datomic `research` database: how far solvers get (question 13), build files, Clojure versions, feature adoption, AI tooling, editors, other ecosystems' common deps, Datomic use |

The first two read Thiago's datasets from the cache. The third needs the database built by `bb after-clone`.

## Pre-registered analysis

`q11.clj` runs question 11 exactly as fixed in the [pre-registration](prereg/lines-of-code.md) (amendments 2 and 4): `clojure -M:cloc`, then `clojure -M -i analysis/q11.clj`. It needs the database and `cloc`. Results: [2026-11-17-q11-lines-of-code](results/2026-11-17-q11-lines-of-code.md).

## Saving output as a results document

Pipe a script's output through `bb results:wrap`. It writes `docs/results/<name>.md` with frontmatter, so the result passes `bb docs:check`:

```sh
bb -cp src analysis/newcomers.bb | bb results:wrap --name 2026-11-12-newcomers \
  --title "Newcomer follow-on" --description "Exploratory; not pre-registered." \
  --source analysis/newcomers.bb --session <session-id>
```

Without `--session`, the document records `session: unrecorded`, which `docs:validate` reports as a warning.
