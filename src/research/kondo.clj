(ns research.kondo
  "Stage 3b (JVM): resolved var references and required namespaces for every
  Clojure file, via clj-kondo's analysis. Static only: nothing is evaluated.
  Writes cache/kondo/<id>.edn per repo, aggregated as (file, var) -> count.
  Resumable: a repo is redone when its kondo file is missing or older than its
  extract file; --force to redo all, --jobs N, --limit N.

  Each file entry is
    :requires              namespaces the file requires (sorted set of strings)
    :usages                {\"ns/var\" count}: one count per source position
    :java                  {\"Class\" or \"Class/member\" count}, imports excluded
    :syntax-errors         clj-kondo :syntax and :file findings
    :analysed?             false when clj-kondo reported an error for the file
                           and returned no analysis for it (an unrecoverable
                           reader error, or an exception on the ns form), or
                           an Error (:file) interrupted it
    :special-forms         references to special forms (if, def, fn*, ...)
    :unresolved            references clj-kondo could not resolve: symbols it
                           gave :clj-kondo/unknown-namespace, and alias/x where
                           the alias is not a namespace (no dot, not in the repo)
    :unrequired-ns-usages  how many :usages came from fully qualified references
                           to namespaces the file does not require (these
                           namespaces are not added to :requires)
    :comment-filtered?     whether the rows of top-level (comment ...) forms
                           (extract :file/comment-rows) were excluded from
                           every count above, :requires included; false when
                           the extract had no comment rows for the file

  Files whose extract :file/role is :config (project.clj, build.boot, ...) are
  not analysed and have no entry."
  (:require [babashka.fs :as fs]
            [clj-kondo.core :as kondo]
            [clj-kondo.impl.core :as kondo-impl]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
   ;; Only these linters report. :syntax and :file record reader errors and
   ;; Errors (e.g. a stack overflow) per file. clj-kondo emits no var-usage for
   ;; a fully qualified reference to a namespace the file never required;
   ;; :unresolved-namespace with :report-duplicates gives one finding per such
   ;; reference (without it, only the first per namespace per file).
   :linters  ^:replace {:syntax {:level :error}
                        :file {:level :error}
                        :unresolved-namespace {:level :warning :report-duplicates true}}})

;; ---------------------------------------------------------------------------
;; Config-dir guard

(defn assert-no-kondo-config!
  "Throw unless clj-kondo would run with no config dir; return nil otherwise.

  run! is called without :config-dir or :filename, so clj-kondo looks for a
  .clj-kondo dir in `start` (the JVM's user.dir) and each parent, and also
  reads CLJ_KONDO_EXTRA_CONFIG_DIR. With a config dir, clj-kondo writes macro
  source marked :clj-kondo/macroexpand-hook in the analysed files into it and
  runs that code to expand calls, so a stranger's repo could run code here."
  ([] (assert-no-kondo-config! (System/getProperty "user.dir")
                               (System/getenv "CLJ_KONDO_EXTRA_CONFIG_DIR")))
  ([start extra-config-dir]
   ;; clj-kondo uses the variable whenever it is non-nil, even when empty.
   (when (some? extra-config-dir)
     (throw (ex-info (str "Refusing to run clj-kondo: CLJ_KONDO_EXTRA_CONFIG_DIR is set to \""
                          extra-config-dir "\". Unset it; with a config dir clj-kondo can run "
                          "macro code from the analysed repos.")
                     {::guard :extra-config-dir})))
   ;; The same function run! uses (clj-kondo.core/run!, 2026.10.26), so the
   ;; answer matches what clj-kondo would find. It throws when .clj-kondo is
   ;; a regular file.
   (let [found (try (kondo-impl/config-dir (io/file start))
                    (catch Exception e
                      (throw (ex-info (str "Refusing to run clj-kondo: " (ex-message e))
                                      {::guard :config-dir} e))))]
     (when found
       (throw (ex-info (str "Refusing to run clj-kondo: found " found " at or above the working "
                            "directory " start ". Move or remove it, or run from a directory "
                            "with no .clj-kondo above it; with a config dir clj-kondo can run "
                            "macro code from the analysed repos.")
                       {::guard :config-dir :dir (str found)}))))))

;; ---------------------------------------------------------------------------
;; Turning clj-kondo's analysis into counts

(def special-forms
  "Clojure's special forms (clojure.lang.Compiler/specials). clj-kondo reports
  them as clojure.core (or cljs.core) vars, but they are not vars and have no
  entry in data/core-vars.edn."
  #{"def" "loop*" "recur" "if" "case*" "let*" "letfn*" "do" "fn*" "quote" "var"
    "import*" "." "set!" "deftype*" "reify*" "try" "throw" "monitor-enter"
    "monitor-exit" "catch" "finally" "new" "&"})

(def ^:private core-namespaces #{"clojure.core" "cljs.core"})

(defn- position
  "Where an analysis entry sits in its file. clj-kondo's duplicate entry for a
  var passed to map, filter, reduce, keep, some, ... has no :name-row/:name-col;
  its :row/:col are the symbol's own position, which is what :name-row and
  :name-col hold on the original entry, so fall back to them."
  [{:keys [filename row col name-row name-col]}]
  [filename row col (or name-row row) (or name-col col)])

(defn- one-per-position
  "One entry per (key-fn entry). In .cljc files clj-kondo reports each position
  once per language; prefer the :clj entry, so a core var is named
  clojure.core/x rather than counted again as cljs.core/x."
  [key-fn entries]
  (for [[_ es] (group-by key-fn entries)]
    (or (some #(when (= :clj (:lang %)) %) es) (first es))))

(defn- var-key [u] (conj (position u) (:name u)))
(defn- java-key [u] (conj (position u) (:class u) (:method-name u)))
(defn- finding-key [f] [(:filename f) (:row f) (:col f) (:ns f) (:name f)])

(defn- usage-name [{:keys [to name]}]
  (if to (str to "/" name) (str name)))

(defn- java-name [{:keys [class method-name]}]
  (str class (when method-name (str "/" method-name))))

(defn- in-ranges?
  "True when row falls inside one of the [start end] ranges (1-based, inclusive)."
  [ranges row]
  (boolean (and row (some (fn [[s e]] (<= s row e)) ranges))))

(defn- keyword-at?
  "True when the source has a ':' at (row, col), both 1-based. clj-kondo also
  reports ::alias/kw keywords with an unknown alias as unresolved namespaces."
  [lines row col]
  (let [^String line (when (and row col) (get lines (dec row)))]
    (boolean (and line (<= 1 col (count line)) (= \: (.charAt line (dec col)))))))

(defn- namespace-like?
  "Whether the namespace part of an unresolved reference names a namespace
  rather than an alias the author never defined: it has a dot (clojure.set,
  goog.string), or a file in the same repo defines it (day1)."
  [ns-name repo-namespaces]
  (or (str/includes? ns-name ".") (contains? repo-namespaces ns-name)))

(defn file-entry
  "One file's counts from its raw clj-kondo entries. comment-rows is the
  extract's :file/comment-rows; when nil, nothing is filtered. lines is a
  delay of the file's lines, read only if it has unresolved-namespace
  findings."
  [{:keys [comment-rows var-usages ns-usages java-usages findings any-analysis?
           repo-namespaces lines]}]
  (let [filter? (some? comment-rows)
        live?   (fn [{:keys [row]}] (not (and filter? (in-ranges? comment-rows row))))
        ;; :refer entries sit in the ns form: a declaration, not a reference.
        ;; An entry with no :row comes from clj-kondo's own expansion of a core
        ;; macro (are -> is; some-> -> let, when, some?, ->): not in the source.
        vus     (->> var-usages (remove :refer) (filter :row) (one-per-position var-key) (filter live?))
        kind    (fn [{:keys [to name]}]
                  (cond (keyword? to)                                             :unresolved
                        (and (core-namespaces (str to)) (special-forms (str name))) :special
                        :else                                                     :var))
        by-kind (group-by kind vus)
        unres   (->> findings
                     (filter #(and (= :unresolved-namespace (:type %)) (:name %)))
                     (one-per-position finding-key)
                     (filter live?)
                     (remove #(keyword-at? @lines (:row %) (:col %))))
        {recovered true unknown false} (group-by #(namespace-like? (str (:ns %)) repo-namespaces) unres)
        ;; Imports are declarations. A Java entry with no position is clj-kondo's
        ;; duplicate for a method value passed to map etc.
        java    (->> java-usages (remove :import) (filter :row) (one-per-position java-key) (filter live?))
        errors  (count (filter #(#{:syntax :file} (:type %)) findings))]
    {:requires             (into (sorted-set) (comp (filter live?) (keep :to) (map str)) ns-usages)
     :usages               (merge-with + (frequencies (map usage-name (:var by-kind)))
                                       (frequencies (map #(str (:ns %) "/" (:name %)) recovered)))
     :java                 (frequencies (map java-name java))
     :syntax-errors        errors
     ;; A reader error that clj-kondo cannot recover from aborts the parse, so
     ;; the file yields no analysis at all. An Error (:file) leaves it partial.
     :analysed?            (not (or (some #(= :file (:type %)) findings)
                                    (and (pos? errors) (not any-analysis?))))
     :special-forms        (count (:special by-kind))
     :unresolved           (+ (count (:unresolved by-kind)) (count unknown))
     :unrequired-ns-usages (count recovered)
     :comment-filtered?    filter?}))

(def ^:private analysis-kinds
  [:namespace-definitions :namespace-usages :var-definitions :var-usages :java-class-usages])

(defn analyse-dir
  "Analyse the Clojure files of one repo, cloned at dir, with clj-kondo.
  summary is the repo's extract summary. Returns {:repo/id :kondo/files {rel {...}}}."
  [{:repo/keys [id] :as summary} dir]
  (assert-no-kondo-config!)
  (let [entries (filter #(and (= :clojure (:file/lang %)) (= :ok (:file/status %))
                              (not= :config (:file/role %)))
                        (:repo/files summary))
        abs     (fn [rel] (str (fs/path dir rel)))
        ->rel   #(str (fs/relativize dir %))]
    (if (empty? entries)
      {:repo/id id :kondo/files {}}
      (let [{:keys [analysis findings]}
            (kondo/run! {:lint (mapv (comp abs :file/path) entries) :cache false :repro true
                         :parallel false :skip-lint false :config kondo-config})
            by-file  (fn [xs] (group-by (comp ->rel :filename) xs))
            vus      (by-file (:var-usages analysis))
            nsu      (by-file (:namespace-usages analysis))
            jcu      (by-file (:java-class-usages analysis))
            fnd      (by-file findings)
            analysed (into #{} (comp (mapcat analysis) (map (comp ->rel :filename))) analysis-kinds)
            repo-ns  (into #{} (map (comp str :name)) (:namespace-definitions analysis))]
        {:repo/id id
         :kondo/files
         (into (sorted-map)
               (for [{rel :file/path :as e} entries]
                 [rel (file-entry {:comment-rows    (:file/comment-rows e)
                                   :var-usages      (vus rel)
                                   :ns-usages       (nsu rel)
                                   :java-usages     (jcu rel)
                                   :findings        (fnd rel)
                                   :any-analysis?   (contains? analysed rel)
                                   :repo-namespaces repo-ns
                                   :lines           (delay (vec (str/split-lines (slurp (abs rel)))))})]))}))))

(defn analyse
  "One repo's extract summary -> {:repo/id :kondo/files {rel {...}}}, reading
  the clone from the cache."
  [summary]
  (analyse-dir summary (cache/repo-dir summary)))

(defn stale?
  "True when the kondo output is missing or older than the extract summary it
  was made from, e.g. after `bb extract --force`."
  [extract-file kondo-file]
  (or (not (fs/exists? kondo-file))
      (pos? (compare (fs/last-modified-time extract-file) (fs/last-modified-time kondo-file)))))

(defn -main [& args]
  (assert-no-kondo-config!)
  (let [force? (some #{"--force"} args)
        opt    (fn [flag] (some-> (second (drop-while #(not= flag %) args)) parse-long))
        jobs   (or (opt "--jobs") 6)
        docs   (->> (fs/glob (cache/path "extract" "clojure") "*.edn")
                    (map #(edn/read-string (slurp (str %))))
                    (filter #(= :ok (:extract/status %))))
        todo   (cond->> (if force? docs (filter #(stale? (extract/out-file %) (out-file (:repo/id %))) docs))
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
                                     (if (::guard (ex-data t))
                                       ;; Not the repo's fault: write nothing, so it is redone.
                                       (do (println (ex-message t)) :refused)
                                       (do (spit (out-file (:repo/id doc))
                                                 (pr-str {:repo/id (:repo/id doc) :kondo/error (str (.getMessage t))}))
                                           :failed))))
                       m      (swap! done update status (fnil inc 0))]
                   (when (zero? (mod (reduce + (vals m)) 250)) (println (str (java.time.LocalTime/now)) m))))))
    (.shutdown pool)
    (.awaitTermination pool 7 TimeUnit/DAYS)
    (println "results:" @done)
    (shutdown-agents)))
