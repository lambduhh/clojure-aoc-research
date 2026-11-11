(ns research.load
  "Stage 4 (JVM): load repos, clone results, extract summaries and clj-kondo
  analysis into Datomic Local. One transaction per repo; every transaction
  points at the run that made it (:tx/run), so `as-of` and history show what
  each run changed.

  Usage: clojure -M:load [--db NAME] [--fresh | --append] [--note TEXT]
                         [--ids 1,2,3] [--limit N]

    --db NAME   database to load into (default \"research\")
    --fresh     delete the database and create it again before loading
    --append    load into a database that already holds earlier runs

  Loading only asserts: a value that disappears between runs (a puzzle the
  mapper no longer assigns, a declaration whose key format changed) is never
  retracted, so it would sit next to the new value. Without --fresh or
  --append the loader therefore refuses a database that already holds runs or
  repos, and leaves it untouched. It also refuses, even with --append, a
  database whose schema predates :dep/key (:dep/coord unique), because
  transacting the current schema doesn't drop that uniqueness. Use --fresh
  for a full load.

  A repo whose inputs can't be read or whose transaction fails is not loaded.
  The run reports loaded and failed counts, records them on the run entity,
  writes the failures to <cache>/load-failures.edn (rewritten every run) and
  exits 1. A refused database exits 2.

  Dependencies are keyed by \"<ecosystem>:<coord>\" (:dep/key), with the
  ecosystem taken from the build file type (:decl/ecosystem), so a deps.edn in
  a Zig repo still declares Maven deps. :dep/canonical-coord applies
  data/dep-aliases.edn so a renamed library can be counted once.

  Dates the repo author controls (the committer date) are validated: one that
  doesn't parse is left out of the repo entity, and the run reports it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [datomic.client.api :as d]
            [research.cache :as cache]
            [research.clone :as clone]
            [research.db :as db]))

(def ecosystem
  "Fallback only, for declarations without :decl/ecosystem (extracts written
  before research.deps tagged them)."
  {:clojure :maven :elixir :hex :ocaml :opam :zig :zig})

(defn parse-instant
  "ISO-8601 date-time with Z or an offset -> java.util.Date, or nil when s is
  nil or doesn't parse (offsets beyond +-18:00, years past 9999, garbage)."
  [s]
  (when (string? s)
    (try (java.util.Date/from (.toInstant (java.time.OffsetDateTime/parse s)))
         (catch java.time.DateTimeException _ nil))))

(defn added-minor
  "Minor version of a Clojure 1.x :added string (\"1.11\" -> 11), or nil.
  Only 1.x versions are numbered this way, so other majors give nil rather
  than a minor that would sort wrongly."
  [s]
  (when-let [[_ minor] (and (string? s) (re-matches #"1\.(\d+)(?:\..*)?" s))]
    (parse-long minor)))

(defn read-aliases
  "data/dep-aliases.edn -> {ecosystem {old-coord canonical-coord}}."
  [f]
  (edn/read-string (slurp (str f))))

(defn canonical-coord
  "The coordinate a library goes by now, or coord itself when it has no alias."
  [aliases eco coord]
  (get-in aliases [eco coord] coord))

(defn dep-key [eco coord] (str (name eco) ":" coord))

(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

(defn- puzzle-key [[y d]] (format "%d-%02d" y d))

(defn- drop-nils
  "Datomic rejects nil values; an absent field becomes an absent attribute."
  [m]
  (into {} (remove (comp nil? val)) m))

(def ^:private date-fields
  [[:repo/created-at :repo/created-at :repo]
   [:repo/pushed-at :repo/pushed-at :repo]
   [:clone/commit-date :repo/commit-date :clone]])

(defn dropped-dates
  "Source keys of dates that are present but don't parse, so repo-tx leaves
  them out. The commit date is author-controlled."
  [repo clone-entry]
  (vec (for [[k _ from] date-fields
             :let [v (get (if (= from :repo) repo clone-entry) k)]
             :when (and (some? v) (nil? (parse-instant v)))]
         k)))

(defn stale-kondo-paths
  "Paths in the kondo output that the extract doesn't list (kondo output older
  than a re-extract). repo-tx skips their usages."
  [extract kondo]
  (let [paths (set (map :file/path (:repo/files extract)))]
    (vec (sort (remove paths (keys (:kondo/files kondo)))))))

(defn repo-tx
  "Everything about one repo as tx-data. Shared entities (puzzles, namespaces,
  vars, deps) get one tempid per transaction and upsert by their unique attr.
  ctx is {:core-vars <data/core-vars.edn> :aliases <data/dep-aliases.edn>}."
  [repo clone-entry extract kondo {:keys [core-vars aliases]}]
  (let [{:repo/keys [id lang]} repo
        shared   (atom {})
        ref!     (fn [k m] (swap! shared assoc k m) k)
        puzzle   (fn [yd] (ref! (str "p:" (puzzle-key yd))
                                {:db/id (str "p:" (puzzle-key yd)) :puzzle/key (puzzle-key yd)
                                 :puzzle/year (first yd) :puzzle/day (second yd)}))
        ns-ref   (fn [n] (ref! (str "n:" n) {:db/id (str "n:" n) :ns/name n}))
        var-ref  (fn [fqn java?]
                   (let [added (get-in core-vars [fqn :added])]
                     (ref! (str "v:" fqn)
                           (cond-> {:db/id (str "v:" fqn) :var/fqn fqn}
                             java? (assoc :var/java? true)
                             added (assoc :var/added added)
                             (added-minor added) (assoc :var/added-minor (added-minor added))
                             (get-in core-vars [fqn :macro]) (assoc :var/macro? true)))))
        dep-ref  (fn [eco coord]
                   (let [k (dep-key eco coord)]
                     (ref! (str "d:" k) {:db/id (str "d:" k) :dep/key k :dep/coord coord :dep/ecosystem eco
                                         :dep/canonical-coord (canonical-coord aliases eco coord)})))
        jv       (:repo/java-version extract)
        dates    (into {} (for [[k attr from] date-fields]
                            [attr (parse-instant (get (if (= from :repo) repo clone-entry) k))]))
        repo-ent (cond-> (drop-nils
                          (merge {:db/id "repo"
                                  :repo/id id :repo/lang lang :repo/full-name (:repo/full-name repo)
                                  :repo/size-kb (:repo/size-kb repo) :repo/stars (:repo/stars repo)
                                  :repo/archived? (:repo/archived? repo)
                                  :repo/clone-status (:clone/status clone-entry)
                                  :repo/sha (:clone/sha clone-entry)
                                  :repo/disk-kb (:clone/disk-kb clone-entry)}
                                 dates))
                   (seq (:repo/build-files extract)) (assoc :repo/build-files (vec (:repo/build-files extract)))
                   (seq (:repo/ai-files extract)) (assoc :repo/ai-files (vec (:repo/ai-files extract)))
                   (seq (:repo/editor-signals extract)) (assoc :repo/editor-signals (vec (:repo/editor-signals extract)))
                   jv (assoc :repo/java-version (first jv) :repo/java-version-source (second jv))
                   (some #(= :unreadable (:decl/status %)) (:repo/declarations extract))
                   (assoc :repo/unreadable-build-files
                          (vec (keep #(when (= :unreadable (:decl/status %)) (:decl/source %))
                                     (:repo/declarations extract)))))
        kfiles   (:kondo/files kondo)
        paths    (set (map :file/path (:repo/files extract)))
        files    (for [f (:repo/files extract)
                       :let [path (:file/path f)
                             k    (get kfiles path)]]
                   (cond-> (drop-nils
                            {:db/id (str "f:" path)
                             :file/key (str (name lang) ":" id ":" path)
                             :file/repo "repo" :file/path path :file/lang (:file/lang f)
                             :file/status (:file/status f) :file/bytes (:file/bytes f)
                             :file/role (:file/role f)
                             :file/template? (:file/template? f)
                             :file/promoted-from (:file/promoted-from f)
                             :file/fingerprint (:file/fingerprint f)
                             :file/runtime (:file/runtime f)
                             :file/module (:file/module f)
                             :file/lines (:file/lines f)
                             :file/code-lines (:file/code-lines f)
                             :file/code-lines-no-comment-forms (:file/code-lines-no-comment-forms f)
                             :file/test-lines (:file/test-lines f)
                             :file/doc-lines (:file/doc-lines f)
                             :file/shared-lines (:file/shared-lines f)})
                     (seq (:puzzle/keys f)) (assoc :file/puzzles (mapv puzzle (:puzzle/keys f))
                                                   :file/puzzle-source (:puzzle/source f))
                     (seq (:requires k)) (assoc :file/requires (mapv ns-ref (:requires k)))
                     k (assoc :file/syntax-errors (:syntax-errors k 0))
                     ;; newer kondo keys; only well-typed values are kept
                     (boolean? (:analysed? k)) (assoc :file/analysed? (:analysed? k))
                     (int? (:special-forms k)) (assoc :file/special-forms (:special-forms k))
                     (int? (:unresolved k)) (assoc :file/unresolved (:unresolved k))))
        usages   (for [[path k] kfiles
                       ;; a path the extract no longer lists has no file
                       ;; entity, and a dangling tempid fails the transaction
                       :when (paths path)
                       [fqns java?] [[(:usages k) false] [(:java k) true]]
                       [fqn n] fqns]
                   {:usage/key (str (name lang) ":" id ":" path "|" fqn)
                    :usage/file (str "f:" path) :usage/var (var-ref fqn java?) :usage/count n})
        decls    (for [{:decl/keys [status source deps kind] :as decl} (:repo/declarations extract)
                       :when (= :ok status)
                       :let [eco (or (:decl/ecosystem decl) (ecosystem lang))]
                       {:dep/keys [coord] :decl/keys [version scope local?]} deps]
                   (cond-> {:decl/key (str (name lang) ":" id "|" source "|" coord "|" (pr-str scope) "|" version)
                            :decl/repo "repo" :decl/dep (dep-ref eco coord) :decl/source source
                            :decl/scope (if (keyword? scope) scope (keyword (str scope)))}
                     version (assoc :decl/version version)
                     (keyword? kind) (assoc :decl/kind kind)
                     (boolean? local?) (assoc :decl/local? local?)))
        ;; realise the lazy seqs before reading `shared`
        body     (doall (concat [repo-ent] files usages decls))]
    (vec (concat (vals @shared) body))))

;; ---------------------------------------------------------------------------
;; Choosing the database

(defn load-plan
  "What to do with the target database: {:action :recreate}, {:action :load}
  or {:refuse <message>}. Inputs: the flags (:fresh? :append?) and what the
  database holds (:populated? :legacy-schema?)."
  [{:keys [fresh? append? populated? legacy-schema? db-name]}]
  (cond
    (and fresh? append?)
    {:refuse "--fresh and --append contradict each other; pass one."}

    fresh?
    {:action :recreate}

    legacy-schema?
    {:refuse (str "Database " db-name " was created before dependencies were keyed by ecosystem "
                  "(:dep/coord is unique there), so hex, opam and zig packages with the same name "
                  "would merge. Load with --fresh, or into a new --db.")}

    (and populated? (not append?))
    {:refuse (str "Database " db-name " already holds an earlier load. Loading only asserts, so "
                  "values that changed since (puzzle mappings, declaration keys) would sit next to "
                  "the new ones. Use --fresh to delete and recreate it, --append to add to it "
                  "anyway, or --db NAME for a new database.")}

    :else
    {:action :load}))

(defn prepare-db!
  "Open db-name per opts (:fresh? :append?). Returns {:conn :mode} or
  {:refuse <message>}. A refused database is inspected but not changed."
  [client db-name {:keys [fresh?] :as opts}]
  (let [inspect (when-not fresh?
                  (let [snapshot (d/db (db/open client db-name))]
                    {:populated? (db/populated? snapshot) :legacy-schema? (db/legacy-dep-schema? snapshot)}))
        plan    (load-plan (merge opts inspect {:db-name db-name}))]
    (cond
      (:refuse plan) plan
      (= :recreate (:action plan)) (do (db/recreate! client db-name)
                                       {:conn (db/connect client db-name) :mode :fresh})
      :else {:conn (db/connect client db-name)
             :mode (if (:populated? inspect) :append :new)})))

;; ---------------------------------------------------------------------------
;; Loading

(defn start-run!
  "Transact the run entity; returns its :run/id."
  [conn {:keys [note mode dataset-commit]}]
  (let [run-id (str (random-uuid))]
    (d/transact conn {:tx-data [(drop-nils {:run/id run-id :run/started (java.util.Date.)
                                            :run/dataset-commit dataset-commit
                                            :run/note note :run/mode mode})]})
    run-id))

(defn finish-run!
  "Record the loaded and failed counts on the run entity."
  [conn run-id {:keys [loaded failed]}]
  (d/transact conn {:tx-data [{:run/id run-id :run/loaded loaded :run/failed (count failed)}]}))

(defn- failure [repo ^Throwable e]
  ;; plain strings and keywords only, so load-failures.edn reads back
  (cond-> {:repo/id (:repo/id repo) :repo/lang (:repo/lang repo)
           :error (str (.getName (class e)) ": " (ex-message e))}
    (keyword? (:cognitect.anomalies/category (ex-data e)))
    (assoc :anomaly (:cognitect.anomalies/category (ex-data e)))))

(defn load-repos!
  "Transact each repo. (read-inputs repo) -> {:extract :kondo :clone-entry};
  it runs inside the per-repo try, so an unreadable input fails that repo only.
  Returns {:loaded n :failed [...] :dropped-dates [[id keys]] :stale-kondo [[id paths]]}."
  [conn run-id repos read-inputs ctx]
  (reduce
   (fn [acc repo]
     (let [acc (try
                 (let [{:keys [extract kondo clone-entry]} (read-inputs repo)
                       tx (repo-tx repo clone-entry extract kondo ctx)]
                   (d/transact conn {:tx-data (conj tx {:db/id "datomic.tx" :tx/run [:run/id run-id]})})
                   (cond-> (update acc :loaded inc)
                     (seq (dropped-dates repo clone-entry))
                     (update :dropped-dates conj [(:repo/id repo) (dropped-dates repo clone-entry)])
                     (seq (stale-kondo-paths extract kondo))
                     (update :stale-kondo conj [(:repo/id repo) (stale-kondo-paths extract kondo)])))
                 (catch Exception e (update acc :failed conj (failure repo e)))
                 ;; deeply nested EDN in an extract
                 (catch StackOverflowError e (update acc :failed conj (failure repo e))))
           done (+ (:loaded acc) (count (:failed acc)))]
       (when (zero? (mod done 500))
         (println (str (java.time.LocalTime/now)) done "repos processed," (count (:failed acc)) "failed"))
       acc))
   {:loaded 0 :failed [] :dropped-dates [] :stale-kondo []}
   repos))

(defn exit-code [{:keys [failed]}] (if (seq failed) 1 0))

(defn write-failures!
  "Write the run's failures as EDN; an empty :failed means a clean run."
  [f m]
  (fs/create-dirs (fs/parent (fs/absolutize f)))
  (spit (str f) (pr-str m)))

(defn parse-args [args]
  (let [opt (fn [flag] (second (drop-while #(not= flag %) args)))
        has (fn [flag] (boolean (some #{flag} args)))]
    {:db-name (or (opt "--db") "research")
     :fresh?  (has "--fresh")
     :append? (has "--append")
     :note    (or (opt "--note") "load")
     :limit   (some-> (opt "--limit") parse-long)
     :ids     (some->> (opt "--ids") (re-seq #"\d+") (map parse-long) set)}))

(defn -main [& args]
  (let [{:keys [db-name note limit ids] :as opts} (parse-args args)
        {:keys [conn mode refuse]} (prepare-db! (db/client) db-name opts)]
    (when refuse
      (binding [*out* *err*] (println "Refusing to load:" refuse))
      (shutdown-agents)
      (System/exit 2))
    (let [run-id  (start-run! conn {:note note :mode mode
                                    :dataset-commit (:dataset-commit (read-edn "data/datasets.edn"))})
          repos   (cond->> (edn/read-string (slurp "data/repos.edn"))
                    ids   (filter (comp ids :repo/id))
                    limit (take limit))
          log     (clone/read-log (clone/log-file))
          ctx     {:core-vars (read-edn "data/core-vars.edn")
                   :aliases   (read-aliases "data/dep-aliases.edn")}
          inputs  (fn [{:repo/keys [id lang]}]
                    {:extract     (read-edn (cache/path "extract" (name lang) (str id ".edn")))
                     :kondo       (when (= lang :clojure) (read-edn (cache/path "kondo" (str id ".edn"))))
                     :clone-entry (get log id)})
          _       (println "db:" db-name "| mode:" mode "| run:" run-id "| repos:" (count repos))
          res     (load-repos! conn run-id repos inputs ctx)
          ffile   (cache/path "load-failures.edn")]
      (finish-run! conn run-id res)
      (write-failures! ffile {:run run-id :db db-name :failed (:failed res)})
      (println "loaded:" (:loaded res) "| failed:" (count (:failed res)) "| of" (count repos))
      (doseq [f (take 5 (:failed res))] (println "  " f))
      (when (seq (:failed res)) (println "  all failures ->" ffile))
      (when (seq (:dropped-dates res))
        (println "repos with an unparseable date left out:" (count (:dropped-dates res))
                 (vec (take 5 (:dropped-dates res)))))
      (when (seq (:stale-kondo res))
        (println "repos whose kondo output lists files the extract doesn't (usages skipped):"
                 (count (:stale-kondo res)) (vec (take 5 (map first (:stale-kondo res))))))
      (shutdown-agents)
      (System/exit (exit-code res)))))
