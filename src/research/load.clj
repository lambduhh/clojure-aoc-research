(ns research.load
  "Stage 4 (JVM): load repos, clone results, extract summaries and clj-kondo
  analysis into Datomic Local. One transaction per repo; every transaction
  points at the run that made it (:tx/run), so `as-of` and history show what
  each run changed.

  Known limitation: re-running only asserts. A value that disappears between
  runs (a file that stopped requiring a namespace) is not retracted."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [datomic.client.api :as d]
            [research.cache :as cache]
            [research.clone :as clone]
            [research.db :as db]))

(def ecosystem {:clojure :maven :elixir :hex :ocaml :opam :zig :zig})

(defn- inst [s] (when s (java.util.Date/from (java.time.Instant/parse s))))
(defn- read-edn [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))

(defn- puzzle-key [[y d]] (format "%d-%02d" y d))

(defn repo-tx
  "Everything about one repo as tx-data. Shared entities (puzzles, namespaces,
  vars, deps) get one tempid per transaction and upsert by their unique attr."
  [repo clone-entry extract kondo core-vars]
  (let [{:repo/keys [id lang]} repo
        shared   (atom {})
        ref!     (fn [k m] (swap! shared assoc k m) k)
        puzzle   (fn [yd] (ref! (str "p:" (puzzle-key yd))
                                {:db/id (str "p:" (puzzle-key yd)) :puzzle/key (puzzle-key yd)
                                 :puzzle/year (first yd) :puzzle/day (second yd)}))
        ns-ref   (fn [n] (ref! (str "n:" n) {:db/id (str "n:" n) :ns/name n}))
        var-ref  (fn [fqn java?]
                   (ref! (str "v:" fqn)
                         (cond-> {:db/id (str "v:" fqn) :var/fqn fqn}
                           java? (assoc :var/java? true)
                           (get-in core-vars [fqn :added]) (assoc :var/added (get-in core-vars [fqn :added]))
                           (get-in core-vars [fqn :macro]) (assoc :var/macro? true))))
        dep-ref  (fn [coord] (ref! (str "d:" coord) {:db/id (str "d:" coord) :dep/coord coord
                                                     :dep/ecosystem (ecosystem lang)}))
        jv       (:repo/java-version extract)
        repo-ent (cond-> {:db/id "repo"
                          :repo/id id :repo/lang lang :repo/full-name (:repo/full-name repo)
                          :repo/size-kb (:repo/size-kb repo) :repo/stars (:repo/stars repo)
                          :repo/archived? (:repo/archived? repo)}
                   (:repo/created-at repo) (assoc :repo/created-at (inst (:repo/created-at repo)))
                   (:repo/pushed-at repo)  (assoc :repo/pushed-at (inst (:repo/pushed-at repo)))
                   clone-entry (assoc :repo/clone-status (:clone/status clone-entry))
                   (:clone/sha clone-entry) (assoc :repo/sha (:clone/sha clone-entry))
                   (:clone/commit-date clone-entry) (assoc :repo/commit-date (inst (:clone/commit-date clone-entry)))
                   (:clone/disk-kb clone-entry) (assoc :repo/disk-kb (:clone/disk-kb clone-entry))
                   (seq (:repo/build-files extract)) (assoc :repo/build-files (vec (:repo/build-files extract)))
                   (seq (:repo/ai-files extract)) (assoc :repo/ai-files (vec (:repo/ai-files extract)))
                   (seq (:repo/editor-signals extract)) (assoc :repo/editor-signals (vec (:repo/editor-signals extract)))
                   jv (assoc :repo/java-version (first jv) :repo/java-version-source (second jv))
                   (some #(= :unreadable (:decl/status %)) (:repo/declarations extract))
                   (assoc :repo/unreadable-build-files
                          (vec (keep #(when (= :unreadable (:decl/status %)) (:decl/source %))
                                     (:repo/declarations extract)))))
        kfiles   (:kondo/files kondo)
        files    (for [f (:repo/files extract)
                       :let [path (:file/path f)
                             fid  (str "f:" path)
                             k    (get kfiles path)]]
                   (cond-> {:db/id fid
                            :file/key (str (name lang) ":" id ":" path)
                            :file/repo "repo" :file/path path :file/lang (:file/lang f)
                            :file/status (:file/status f) :file/bytes (:file/bytes f)}
                     (:file/role f) (assoc :file/role (:file/role f))
                     (:file/runtime f) (assoc :file/runtime (:file/runtime f))
                     (:file/module f) (assoc :file/module (:file/module f))
                     (:file/lines f) (assoc :file/lines (:file/lines f))
                     (:file/code-lines f) (assoc :file/code-lines (:file/code-lines f))
                     (:file/code-lines-no-comment-forms f)
                     (assoc :file/code-lines-no-comment-forms (:file/code-lines-no-comment-forms f))
                     (seq (:puzzle/keys f)) (assoc :file/puzzles (mapv puzzle (:puzzle/keys f))
                                                   :file/puzzle-source (:puzzle/source f))
                     (seq (:requires k)) (assoc :file/requires (mapv ns-ref (:requires k)))
                     k (assoc :file/syntax-errors (:syntax-errors k 0))))
        usages   (for [[path k] kfiles
                       [fqns java?] [[(:usages k) false] [(:java k) true]]
                       [fqn n] fqns]
                   {:usage/key (str (name lang) ":" id ":" path "|" fqn)
                    :usage/file (str "f:" path) :usage/var (var-ref fqn java?) :usage/count n})
        decls    (for [{:decl/keys [status source deps]} (:repo/declarations extract)
                       :when (= :ok status)
                       {:dep/keys [coord] :decl/keys [version scope]} deps]
                   (cond-> {:decl/key (str (name lang) ":" id "|" source "|" coord "|" (pr-str scope))
                            :decl/repo "repo" :decl/dep (dep-ref coord) :decl/source source
                            :decl/scope (if (keyword? scope) scope (keyword (str scope)))}
                     version (assoc :decl/version version)))
        ;; realise the lazy seqs before reading `shared`
        body     (doall (concat [repo-ent] files usages decls))]
    (vec (concat (vals @shared) body))))

(defn -main [& args]
  (let [opt     (fn [flag] (second (drop-while #(not= flag %) args)))
        db-name (or (opt "--db") "research")
        limit   (some-> (opt "--limit") parse-long)
        conn    (db/connect db-name)
        run-id  (str (random-uuid))
        _       (d/transact conn {:tx-data [{:run/id run-id :run/started (java.util.Date.)
                                             :run/dataset-commit (:dataset-commit (read-edn "data/datasets.edn"))
                                             :run/note (or (opt "--note") "load")}]})
        ids     (some->> (opt "--ids") (re-seq #"\d+") (map parse-long) set)
        repos   (cond->> (edn/read-string (slurp "data/repos.edn"))
                  ids   (filter (comp ids :repo/id))
                  limit (take limit))
        log     (clone/read-log (clone/log-file))
        core    (read-edn "data/core-vars.edn")
        n       (atom 0)
        failed  (atom [])]
    (println "db:" db-name "| run:" run-id "| repos:" (count repos))
    (doseq [{:repo/keys [id lang] :as repo} repos]
      (let [extract (read-edn (cache/path "extract" (name lang) (str id ".edn")))
            kondo   (when (= lang :clojure) (read-edn (cache/path "kondo" (str id ".edn"))))
            tx      (repo-tx repo (get log id) extract kondo core)]
        (try
          (d/transact conn {:tx-data (conj tx {:db/id "datomic.tx" :tx/run [:run/id run-id]})})
          (catch Exception e (swap! failed conj {:repo/id id :error (ex-message e)})))
        (when (zero? (mod (swap! n inc) 500)) (println (str (java.time.LocalTime/now)) @n "repos loaded"))))
    (println "loaded:" @n "| failed:" (count @failed))
    (doseq [f (take 5 @failed)] (println "  " f))
    (shutdown-agents)
    (System/exit 0)))
