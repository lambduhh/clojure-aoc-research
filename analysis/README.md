<!-- DRAFT: AI-drafted; pending Jordan's review. -->

# analysis/

Exploratory insight scripts. **None of these is pre-registered.** Treat them as descriptive, and check them before citing.

| Script | Run | Output |
|---|---|---|
| `dataset_insights.bb` | `bb -cp src analysis/dataset_insights.bb` | AoC-named repos per year by language, and Thiago's follow-on method (all owners) |
| `newcomers.bb` | `bb -cp src analysis/newcomers.bb` | The same follow-on method, for newcomers only |
| `db_insights.clj` | `clojure -M -i analysis/db_insights.clj` | From the Datomic `research` database: how far solvers get (question 13), build files, Clojure versions, feature adoption, AI tooling, editors, other ecosystems' common deps, Datomic use |

The first two read Thiago's datasets from the cache. The third needs the database built by `bb after-clone`.
