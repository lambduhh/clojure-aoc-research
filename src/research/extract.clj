(ns research.extract
  "Stage 3 runner: summarise every cloned repo into cache/extract/<lang>/<id>.edn.
  Resumable: existing summaries are kept unless --force."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [research.cache :as cache]
            [research.clone :as clone]
            [research.inventory :as inv])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn out-file [{:repo/keys [lang id]}] (cache/path "extract" (name lang) (str id ".edn")))

(defn extract! [repo]
  (let [out (out-file repo)]
    (fs/create-dirs (fs/parent out))
    (try
      (spit out (pr-str (assoc (inv/summarize repo (cache/repo-dir repo)) :extract/status :ok)))
      :ok
      (catch Throwable t
        (spit out (pr-str {:repo/id (:repo/id repo) :repo/lang (:repo/lang repo)
                           :extract/status :failed :extract/error (str (.getMessage t))}))
        :failed))))

(defn -main [& args]
  (let [force?  (some #{"--force"} args)
        jobs    (or (some-> (second (drop-while #(not= "--jobs" %) args)) parse-long) 8)
        repos   (edn/read-string (slurp "data/repos.edn"))
        log     (clone/read-log (clone/log-file))
        cloned  (filter #(= :cloned (get-in log [(:repo/id %) :clone/status])) repos)
        todo    (vec (if force? cloned (remove #(fs/exists? (out-file %)) cloned)))
        results (atom {})
        pool    (Executors/newFixedThreadPool jobs)]
    (println "cloned:" (count cloned) "| to extract:" (count todo))
    (doseq [r todo]
      (.submit pool ^Runnable (fn [] (swap! results update (extract! r) (fnil inc 0)))))
    (.shutdown pool)
    (.awaitTermination pool 7 TimeUnit/DAYS)
    (println "results:" @results)))

(defn summary []
  (let [files (fs/glob (cache/path "extract") "**.edn")
        docs  (map #(edn/read-string {:default tagged-literal} (slurp (str %))) files)
        sfiles (mapcat :repo/files docs)
        sol   (filter #(= :solution (:file/role %)) sfiles)]
    (println "repos:" (count docs) (frequencies (map :extract/status docs)))
    (println "source files:" (count sfiles) "by lang:" (frequencies (map :file/lang sfiles)))
    (println "solution files:" (count sol) "mapped:" (count (filter (comp seq :puzzle/keys) sol)))
    (println "year sources:" (frequencies (keep :puzzle/source sfiles)))
    (println "declarations:" (frequencies (map :decl/status (mapcat :repo/declarations docs))))
    (println "ai files:" (frequencies (mapcat :repo/ai-files docs)))
    (println "editor signals:" (frequencies (mapcat :repo/editor-signals docs)))
    (println "java versions declared:" (count (keep :repo/java-version docs)))))
