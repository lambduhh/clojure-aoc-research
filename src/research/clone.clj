(ns research.clone
  "Stage 2: shallow-clone every repo in data/repos.edn into the cache.
  Resumable: an append-only log records one EDN map per attempt."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [research.cache :as cache]
            [research.names :as names])
  (:import (java.util.concurrent Executors TimeUnit)))

(def size-cap-kb 10240)          ; GitHub's :size, in KB (includes history)
(def timeout-ms (* 3 60 1000))
(def terminal-statuses #{:cloned :skipped-size :missing})

(defn log-file [] (cache/path "clone-log.edn"))

(defn read-log
  "repo id -> its latest log entry."
  [file]
  (if-not (fs/exists? file)
    {}
    (with-open [r (io/reader file)]
      (reduce (fn [m line]
                (if (str/blank? line)
                  m
                  (let [e (edn/read-string line)] (assoc m (:repo/id e) e))))
              {}
              (line-seq r)))))

(defn pending
  "Repos still to attempt. Failures and timeouts are retried only on request."
  [repos log {:keys [retry-failed]}]
  (remove (fn [{:repo/keys [id]}]
            (let [status (get-in log [id :clone/status])]
              (or (terminal-statuses status)
                  (and (not retry-failed) (#{:failed :timeout} status)))))
          repos))

(defn over-size-cap? [{:repo/keys [size-kb]}] (> size-kb size-cap-kb))

(defn classify-error
  "Git's stderr -> a status keyword."
  [stderr]
  (cond
    (re-find #"(?i)repository not found|not found|could not read Username|Authentication failed|access denied" stderr) :missing
    :else :failed))

(def ^:private git-env
  {"GIT_TERMINAL_PROMPT" "0"     ; never prompt for credentials
   "GIT_ASKPASS"         "/usr/bin/false" ; ...and never run an askpass helper
   "SSH_ASKPASS"         "/usr/bin/false"
   "GIT_LFS_SKIP_SMUDGE" "1"     ; don't download LFS blobs
   "GIT_CONFIG_NOSYSTEM" "1"})

(def ^:private git-safety
  ;; No credential helpers, no hooks (including the user's global ones),
  ;; symlinks checked out as plain files so later stages can't escape the repo.
  ["-c" "credential.helper=" "-c" "core.askPass=/usr/bin/false" "-c" "core.hooksPath=/dev/null"
   "-c" "core.symlinks=false" "-c" "init.templateDir=" "-c" "protocol.file.allow=never"])

(defn- git [dir & args]
  (-> (apply p/shell {:dir dir :out :string :err :string :continue true :extra-env git-env}
             "git" (concat git-safety args))
      :out str/trim))

(defn- disk-kb [dir]
  (some-> (p/shell {:out :string :continue true} "du" "-sk" (str dir)) :out (str/split #"\s+") first parse-long))

(defn clone!
  "Attempt one repo. Returns the log entry; never throws."
  [repo]
  (let [started (System/currentTimeMillis)
        base    (select-keys repo [:repo/id :repo/lang :repo/full-name])
        entry   (fn [m] (merge base m {:clone/at         (str (java.time.Instant/now))
                                       :clone/elapsed-ms (- (System/currentTimeMillis) started)}))]
    (try
      (if (over-size-cap? repo)
        (entry {:clone/status :skipped-size :clone/size-kb (:repo/size-kb repo)})
        (let [final (cache/repo-dir repo)
              tmp   (cache/path "tmp" (str (:repo/id repo) "-" (random-uuid)))]
          (fs/create-dirs (fs/parent final))
          (fs/create-dirs (fs/parent tmp))
          (when (fs/exists? final) (fs/delete-tree final))
          (let [proc (apply p/process {:out :string :err :string :extra-env git-env}
                                "git" (concat git-safety
                                              ["clone" "--depth" "1" "--single-branch" "--no-tags" "--quiet"
                                               "--config" "core.symlinks=false"
                                               (names/clone-url (:repo/full-name repo)) tmp]))
                res  (deref proc timeout-ms ::timeout)]
            (cond
              (= res ::timeout)
              (do (p/destroy-tree proc) (fs/delete-tree tmp)
                  (entry {:clone/status :timeout}))

              (zero? (:exit res))
              (do (fs/move tmp final)
                  (entry {:clone/status      :cloned
                          :clone/sha         (git final "rev-parse" "HEAD")
                          :clone/commit-date (git final "log" "-1" "--format=%cI")
                          :clone/disk-kb     (disk-kb final)}))

              :else
              (let [err (str (:err res))]
                (fs/delete-tree tmp)
                (entry {:clone/status (classify-error err)
                        :clone/error  (subs err 0 (min 500 (count err)))}))))))
      (catch Throwable t
        (entry {:clone/status :failed :clone/error (str (.getMessage t))})))))

(defn- parse-args [args]
  (loop [[a b & more :as args] args opts {:jobs 8}]
    (case a
      nil              opts
      "--limit"        (recur more (assoc opts :limit (parse-long b)))
      "--jobs"         (recur more (assoc opts :jobs (parse-long b)))
      "--retry-failed" (recur (cons b more) (assoc opts :retry-failed true))
      (throw (ex-info (str "Unknown flag: " a) {:args args})))))

(defn print-status []
  (let [log (read-log (log-file))]
    (println "attempted:" (count log))
    (doseq [[s n] (sort-by key (frequencies (map :clone/status (vals log))))]
      (println (format "  %-14s %6d" (name s) n)))
    (println "disk:" (format "%.2f GB" (/ (reduce + (keep :clone/disk-kb (vals log))) 1e6)))))

(defn -main [& args]
  (let [opts    (parse-args (remove nil? args))
        repos   (edn/read-string (slurp "data/repos.edn"))
        todo    (cond->> (pending repos (read-log (log-file)) opts)
                  (:limit opts) (take (:limit opts)))
        todo    (vec todo)
        lock    (Object.)
        done    (atom 0)
        pool    (Executors/newFixedThreadPool (:jobs opts))]
    (fs/create-dirs (cache/root))
    (when (fs/exists? (cache/path "tmp")) (fs/delete-tree (cache/path "tmp")))
    (println "cache:" (cache/root) "| to attempt:" (count todo) "| jobs:" (:jobs opts))
    (with-open [w (io/writer (log-file) :append true)]
      (doseq [repo todo]
        (.submit pool ^Runnable
                 (fn []
                   (let [e (clone! repo)]
                     (locking lock
                       (.write w (str (pr-str e) "\n"))
                       (.flush w)
                       (let [n (swap! done inc)]
                         (when (or (zero? (mod n 100)) (= n (count todo)))
                           (println (str (java.time.LocalTime/now)) n "/" (count todo)))))))))
      (.shutdown pool)
      (.awaitTermination pool 7 TimeUnit/DAYS))
    (print-status)))
