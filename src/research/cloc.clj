(ns research.cloc
  "Question 11's primary measure: cloc code lines for every included solution
  file (prereg amendment 4). JVM only, because the file list comes from the
  Datomic database.

  clojure -M:cloc [--jobs N]

  Writes <cache>/cloc/counts.edn:
    {:cloc-version \"2.10\" :args [...] :counts {file-key code-lines}
     :uncounted #{file-key ...} :bad-path #{file-key ...}}
  A file in :uncounted had no cloc result (an unrecognised format such as
  .livemd); its whole solution is dropped by the analysis, never part-summed.

  cloc only reads files; nothing from the cloned repos is executed. Paths come
  from untrusted repos, so they are passed in a list file (never a shell), and
  any path that is absolute, contains `..` or a line break is refused."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [research.cache :as cache]
            [research.db :as db]))

(def cloc-args
  "Fixed by prereg amendment 4. Changing these changes the measure."
  ["--by-file" "--json" "--quiet" "--skip-uniqueness" "--force-lang=Clojure,bb"])

(def included-files-query
  ;; Same inclusion rule as research.queries/funnel and analysis/q11.clj.
  '[:find ?key ?lang ?id ?path
    :where [?f :file/role :solution] [?f :file/status :ok] [?f :file/puzzles]
           (not [?f :file/template? true])
           [?f :file/repo ?r] [?r :repo/lang ?lang] [?f :file/lang ?lang]
           [?r :repo/id ?id] [?f :file/path ?path] [?f :file/key ?key]])

(defn safe-rel-path? [path]
  (and (string? path)
       (not (str/blank? path))
       (not (fs/absolute? path))
       (not (re-find #"[\r\n]" path))
       (not-any? #{".."} (str/split path #"/"))))

(defn cloc-version []
  (str/trim (:out (p/shell {:out :string} "cloc" "--version"))))

(defn- run-batch
  "Run cloc on one batch of [file-key abs-path]; return {file-key code}."
  [batch]
  (let [list-file (fs/create-temp-file {:prefix "cloc-list-" :suffix ".txt"})]
    (try
      (spit (str list-file) (str/join "\n" (map second batch)))
      (let [{:keys [out exit err]} (p/shell {:out :string :err :string :continue true}
                                            (into ["cloc" (str "--list-file=" list-file)] cloc-args))]
        (when-not (zero? exit)
          (throw (ex-info "cloc failed" {:exit exit :err err})))
        (let [by-path (if (str/blank? out) {} (json/read-str out))]
          (into {} (keep (fn [[k path]]
                           (when-let [row (get by-path path)]
                             [k (long (get row "code"))])))
                batch)))
      (finally (fs/delete-if-exists list-file)))))

(defn count-files
  "rows: [[key lang id path] ...] -> the counts.edn map."
  [rows {:keys [jobs batch-size] :or {jobs 8 batch-size 2000}}]
  (let [{safe true unsafe false} (group-by #(safe-rel-path? (nth % 3)) rows)
        files (for [[k lang id path] safe]
                [k (str (fs/path (cache/repo-dir {:repo/lang lang :repo/id id}) path))])
        pool (java.util.concurrent.Executors/newFixedThreadPool jobs)
        counts (try
                 (->> (partition-all batch-size files)
                      (mapv (fn [b] (.submit pool ^Callable (fn [] (run-batch b)))))
                      (map #(.get ^java.util.concurrent.Future %))
                      (apply merge {}))
                 (finally (.shutdown pool)))]
    {:cloc-version (cloc-version)
     :args cloc-args
     :counts counts
     :uncounted (into #{} (comp (map first) (remove counts)) files)
     :bad-path (into #{} (map first) unsafe)}))

(defn out-file [] (cache/path "cloc" "counts.edn"))

(defn -main [& args]
  (let [jobs (or (some->> (drop-while #(not= "--jobs" %) args) second parse-long) 8)
        dbv (d/db (d/connect (db/client) {:db-name "research"}))
        rows (d/q included-files-query dbv)
        _ (println "included files:" (count rows) "jobs:" jobs)
        result (count-files rows {:jobs jobs})
        out (out-file)]
    (fs/create-dirs (fs/parent out))
    (spit out (pr-str result))
    (println "cloc" (:cloc-version result) "counted:" (count (:counts result))
             "uncounted:" (count (:uncounted result)) "bad paths:" (count (:bad-path result)))
    (println "wrote" out)
    (shutdown-agents)))
