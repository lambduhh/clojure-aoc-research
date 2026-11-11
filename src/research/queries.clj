(ns research.queries
  "One named Datalog query per research question. Each takes a db value.
  Results are provisional until the pre-registered checks pass (see
  prereg/lines-of-code.md); questions 11-13 are deliberately not here yet."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]))

;; ---------------------------------------------------------------------------
;; Q15: the funnel, by language

(defn funnel [db]
  {:repos-by-status
   (d/q '[:find ?lang ?status (count ?r)
          :where [?r :repo/lang ?lang] [?r :repo/clone-status ?status]] db)
   :included-solution-files
   (d/q '[:find ?lang (count ?f)
          :where [?f :file/role :solution] [?f :file/status :ok] [?f :file/puzzles]
                 (not [?f :file/template? true])
                 [?f :file/repo ?r] [?r :repo/lang ?lang] [?f :file/lang ?lang]] db)})

;; ---------------------------------------------------------------------------
;; Q1: which libraries do Clojure AoC solvers declare? (distinct repos)

(def ^:private q1-query
  '[:find ?coord (count-distinct ?r)
    :in $ ?eco ?lang
    :where [?r :repo/lang ?lang]
           [?x :decl/repo ?r]
           [?x :decl/local? false]
           [?x :decl/dep ?dep]
           [?dep :dep/ecosystem ?eco]
           [?dep :dep/canonical-coord ?coord]])

(defn declared-deps
  "[[canonical-coord repos] ...], most-declared first. Local/path deps excluded."
  ([db] (declared-deps db :maven :clojure))
  ([db eco lang]
   (->> (d/q q1-query db eco lang)
        (sort-by (comp - second)))))

(defn declared-deps-by-year
  "Q1 by repo creation year: [[coord year repos] ...]."
  [db coords]
  (d/q '[:find ?coord ?year (count-distinct ?r)
         :in $ [?coord ...]
         :where [?dep :dep/canonical-coord ?coord] [?dep :dep/ecosystem :maven]
                [?x :decl/dep ?dep] [?x :decl/local? false] [?x :decl/repo ?r]
                [?r :repo/lang :clojure] [?r :repo/created-at ?c]
                [(.getYear ^java.time.ZonedDateTime (.atZone ^java.util.Date ?c java.time.ZoneOffset/UTC)) ?year]]
       db coords))

;; ---------------------------------------------------------------------------
;; Q2: which namespaces do Clojure solution files require? (distinct repos)

(defn required-namespaces [db]
  (->> (d/q '[:find ?ns (count-distinct ?r)
              :where [?f :file/requires ?n] [?n :ns/name ?ns]
                     [?f :file/repo ?r] [?r :repo/lang :clojure]
                     (not [?f :file/role :config])]
            db)
       (sort-by (comp - second))))

;; ---------------------------------------------------------------------------
;; Q4: the template's deps against what solvers use

(defn template-coverage
  "For each dep in the template's deps.edn: how many Clojure repos declare it.
  `template-deps` is a seq of canonical coordinates."
  [db template-deps]
  (let [counts (into {} (declared-deps db))]
    (mapv (fn [c] [c (get counts c 0)]) template-deps)))

(defn template-deps-from-file
  "Canonical coordinates of the :deps and alias :extra-deps in a deps.edn."
  [path]
  (let [m (edn/read-string (slurp path))
        coord (fn [sym] (if (namespace sym) (str sym) (str sym "/" sym)))]
    (vec (distinct (concat (map coord (keys (:deps m)))
                           (for [[_ a] (:aliases m) [lib _] (:extra-deps a) :when (symbol? lib)] (coord lib))
                           (for [[_ a] (:aliases m) [lib _] (:replace-deps a) :when (symbol? lib)] (coord lib)))))))
