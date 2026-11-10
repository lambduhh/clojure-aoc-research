(ns research.kondo
  "Stage 3b (JVM): resolved var usages and required namespaces for every
  Clojure file, via clj-kondo's analysis. Static only: nothing is evaluated.
  Writes cache/kondo/<id>.edn per repo, aggregated as (file, var) -> count.
  Resumable; --force to redo, --jobs N, --limit N."
  (:require [babashka.fs :as fs]
            [clj-kondo.core :as kondo]
            [clojure.edn :as edn]
            [research.cache :as cache]
            [research.extract :as extract])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn out-file [id] (cache/path "kondo" (str id ".edn")))

(def kondo-config
  {:analysis {:var-usages true
              :namespace-usages true
              :namespace-definitions true
              :java-class-usages true
              :java-member-definitions false}
   :output   {:canonical-paths false}
   :linters  ^:replace {}})

(defn- usage-name [{:keys [to name]}]
  (if to (str to "/" name) (str name)))

(defn analyse
  "One repo's extract summary -> {:repo/id :kondo/files {rel {...}}}."
  [{:repo/keys [id] :as summary}]
  (let [dir   (cache/repo-dir summary)
        rels  (->> (:repo/files summary)
                   (filter #(and (= :clojure (:file/lang %)) (= :ok (:file/status %))))
                   (map :file/path))
        abs   (mapv #(str (fs/path dir %)) rels)
        ->rel #(str (fs/relativize dir %))]
    (if (empty? abs)
      {:repo/id id :kondo/files {}}
      (let [{:keys [analysis findings]}
            (kondo/run! {:lint abs :cache false :repro true :parallel false
                         :skip-lint true :config kondo-config})
            by-file (fn [k] (group-by (comp ->rel :filename) (get analysis k)))
            vus     (by-file :var-usages)
            nsu     (by-file :namespace-usages)
            jcu     (by-file :java-class-usages)
            syntax  (frequencies (map (comp ->rel :filename) (filter #(= :syntax (:type %)) findings)))]
        {:repo/id id
         :kondo/files
         (into (sorted-map)
               (for [rel rels]
                 [rel {:requires    (into (sorted-set) (keep (comp str :to)) (nsu rel))
                       :usages      (frequencies (map usage-name (vus rel)))
                       :java        (frequencies (map #(str (:class %) (when (:method-name %) (str "/" (:method-name %)))) (jcu rel)))
                       :syntax-errors (get syntax rel 0)}]))}))))

(defn -main [& args]
  (let [force? (some #{"--force"} args)
        opt    (fn [flag] (some-> (second (drop-while #(not= flag %) args)) parse-long))
        jobs   (or (opt "--jobs") 6)
        docs   (->> (fs/glob (cache/path "extract" "clojure") "*.edn")
                    (map #(edn/read-string (slurp (str %))))
                    (filter #(= :ok (:extract/status %))))
        todo   (cond->> (if force? docs (remove #(fs/exists? (out-file (:repo/id %))) docs))
                 (opt "--limit") (take (opt "--limit")))
        todo   (vec todo)
        done   (atom {})
        pool   (Executors/newFixedThreadPool jobs)]
    (fs/create-dirs (cache/path "kondo"))
    (println "extracted Clojure repos:" (count docs) "| to analyse:" (count todo))
    (doseq [doc todo]
      (.submit pool ^Runnable
               (fn []
                 (let [status (try (spit (out-file (:repo/id doc)) (pr-str (analyse doc))) :ok
                                   (catch Throwable t
                                     (spit (out-file (:repo/id doc))
                                           (pr-str {:repo/id (:repo/id doc) :kondo/error (str (.getMessage t))}))
                                     :failed))]
                   (let [m (swap! done update status (fnil inc 0))]
                     (when (zero? (mod (reduce + (vals m)) 250)) (println (str (java.time.LocalTime/now)) m)))))))
    (.shutdown pool)
    (.awaitTermination pool 7 TimeUnit/DAYS)
    (println "results:" @done)
    (shutdown-agents)))
