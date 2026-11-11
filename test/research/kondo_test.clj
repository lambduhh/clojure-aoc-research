(ns research.kondo-test
  "JVM tests for research.kondo. Every source analysed here is synthetic and
  written under /tmp; nothing is read from the clone cache."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [research.kondo :as k]))

(defn- tmp-dir [] (str (fs/create-temp-dir {:dir "/tmp" :prefix "kondo-test-"})))

(defn- analyse-sources
  "Write {rel source} under a fresh /tmp dir and analyse it as one repo.
  `entries` optionally overrides extract-file fields per rel (e.g. :file/role,
  :file/comment-rows). Returns :kondo/files."
  ([sources] (analyse-sources sources {}))
  ([sources entries]
   (let [dir (tmp-dir)]
     (doseq [[rel src] sources]
       (fs/create-dirs (fs/parent (fs/path dir rel)))
       (spit (str (fs/path dir rel)) src))
     (try
       (:kondo/files
        (k/analyse-dir {:repo/id 1
                        :repo/lang :clojure
                        :repo/files (vec (for [[rel _] sources]
                                           (merge {:file/path rel :file/lang :clojure
                                                   :file/status :ok :file/role :util}
                                                  (get entries rel))))}
                       dir))
       (finally (fs/delete-tree dir))))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [2]: a var passed to map/filter/reduce/keep/some was
;; reported twice by clj-kondo at the same position.

(deftest higher-order-arguments-count-once
  (let [{:keys [usages java]}
        (get (analyse-sources
              {"a.clj" (str "(ns a)\n"
                            "(defn f [xs]\n"
                            "  (filter even? xs) (reduce + xs) (map inc xs)\n"
                            "  (keep identity xs) (some odd? xs) (mapv dec xs)\n"
                            "  (map Character/isDigit \"a1\"))\n")})
             "a.clj")]
    (is (= 1 (usages "clojure.core/even?")))
    (is (= 1 (usages "clojure.core/+")))
    (is (= 1 (usages "clojure.core/inc")))
    (is (= 1 (usages "clojure.core/identity")))
    (is (= 1 (usages "clojure.core/odd?")))
    (is (= 1 (usages "clojure.core/dec")))
    (is (= 2 (usages "clojure.core/map")) "two calls: (map inc xs) and (map Character/isDigit ..)")
    (testing "the Java analogue: a method value passed to map has a position-less duplicate"
      (is (= {"java.lang.Character/isDigit" 1} java)))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [4]: .cljc files are analysed once per language.

(deftest cljc-positions-count-once
  (let [{:keys [usages]}
        (get (analyse-sources
              {"b.cljc" (str "(ns b (:require [aoc.util :as u] [clojure.string :as s]))\n"
                             "(u/parse (s/trim \" x \")) (map inc [1])\n"
                             "#?(:clj (str 1) :cljs (keyword \"x\"))\n")})
             "b.cljc")]
    (is (= 1 (usages "aoc.util/parse")))
    (is (= 1 (usages "clojure.string/trim")))
    (testing "core vars come out once, under the :clj name"
      (is (= 1 (usages "clojure.core/map")))
      (is (= 1 (usages "clojure.core/inc")))
      (is (nil? (usages "cljs.core/map")))
      (is (nil? (usages "cljs.core/inc"))))
    (testing "a branch that exists only for one language is still counted"
      (is (= 1 (usages "clojure.core/str")))
      (is (= 1 (usages "cljs.core/keyword"))))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [5]: syntax errors were never recorded, and a file whose
;; parse failed looked like a file with no usages.

(deftest syntax-errors-are-recorded
  (let [files (analyse-sources
               {"ok.clj"        "(ns ok)\n(inc 1)\n"
                "recovered.clj" "(ns recovered)\n(inc 1)\n(defn g [] (dec 2)))\n(inc 3)\n"
                "fatal.clj"     "(ns fatal)\n(inc 1)\n(str \"never closed\n"})]
    (testing "a clean file"
      (is (= 0 (get-in files ["ok.clj" :syntax-errors])))
      (is (true? (get-in files ["ok.clj" :analysed?]))))
    (testing "a recoverable error is counted and the analysis is kept"
      (is (pos? (get-in files ["recovered.clj" :syntax-errors])))
      (is (true? (get-in files ["recovered.clj" :analysed?])))
      (is (= 2 (get-in files ["recovered.clj" :usages "clojure.core/inc"]))))
    (testing "a reader error that loses the whole file is flagged"
      (is (pos? (get-in files ["fatal.clj" :syntax-errors])))
      (is (false? (get-in files ["fatal.clj" :analysed?])))
      (is (= {} (get-in files ["fatal.clj" :usages]))))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [6]: comment forms, special forms and :refer entries were
;; counted as usages.

(def ^:private comment-src
  (str "(ns c (:require [clojure.set :as set]))\n"    ; 1
       "(inc 1)\n"                                    ; 2
       "(comment\n"                                   ; 3
       "  (frequencies [1])\n"                        ; 4
       "  (require '[clojure.walk :as walk])\n"       ; 5
       "  (java.util.HashMap.))\n"                    ; 6
       "(dec 2)\n"))                                  ; 7

(deftest comment-forms-are-excluded
  (testing "rows from the extract's :file/comment-rows are excluded from every count"
    (let [f (get (analyse-sources {"c.clj" comment-src} {"c.clj" {:file/comment-rows [[3 6]]}}) "c.clj")]
      (is (true? (:comment-filtered? f)))
      (is (= {"clojure.core/inc" 1 "clojure.core/dec" 1} (:usages f)))
      (is (= {} (:java f)))
      (is (= #{"clojure.set"} (:requires f)))))
  (testing "an empty vector means the file parsed and has no comment forms"
    (let [f (get (analyse-sources {"c.clj" comment-src} {"c.clj" {:file/comment-rows []}}) "c.clj")]
      (is (true? (:comment-filtered? f)))
      (is (= 1 (get-in f [:usages "clojure.core/frequencies"])))))
  (testing "nil or absent comment rows: nothing is filtered, and that is recorded"
    (doseq [entry [{:file/comment-rows nil} {}]]
      (let [f (get (analyse-sources {"c.clj" comment-src} {"c.clj" entry}) "c.clj")]
        (is (false? (:comment-filtered? f)))
        (is (= 1 (get-in f [:usages "clojure.core/frequencies"])))
        (is (= {"java.util.HashMap" 1} (:java f)))
        (is (contains? (:requires f) "clojure.walk"))))))

(deftest special-forms-are-counted-separately
  (let [f (get (analyse-sources
                {"s.clj" (str "(ns s)\n"
                              "(def x 1)\n"                                  ; def
                              "(do (if x (inc x) 0))\n"                      ; do if
                              "(loop [i 0] (when (< i 3) (recur (inc i))))\n" ; recur
                              "(map #(dec %) [1])\n"                         ; fn*
                              "(. \"abc\" toUpperCase)\n"                    ; .
                              "(try (throw (ex-info \"a\" {})) (catch Exception _ nil))\n" ; try throw
                              "(new java.util.HashMap)\n")})                 ; new
               "s.clj")
        usages (:usages f)]
    (is (= 9 (:special-forms f)))
    (doseq [sf ["def" "do" "if" "recur" "fn*" "." "try" "throw" "new"]]
      (is (nil? (usages (str "clojure.core/" sf))) sf))
    (testing "macros and functions stay"
      (is (= 1 (usages "clojure.core/loop")))
      (is (= 1 (usages "clojure.core/when")))
      (is (= 2 (usages "clojure.core/inc")))
      (is (= 1 (usages "clojure.core/dec"))))))

(deftest macro-expansion-artifacts-are-not-usages
  ;; clj-kondo expands some core macros itself and reports the vars of the
  ;; expansion with no source position (are -> is, do; some-> -> some?, ->,
  ;; when, let), and reports a var inside an `are` body once per row of values.
  (let [{:keys [usages]}
        (get (analyse-sources
              {"m.clj" (str "(ns m (:require [clojure.test :refer [are deftest]]))\n"
                            "(deftest t (are [x] (= x 1) 1 1))\n"
                            "(some-> 1 inc)\n"
                            "(cond-> 1 true dec)\n")})
             "m.clj")]
    (is (= {"clojure.test/deftest" 1 "clojure.test/are" 1 "clojure.core/=" 1
            "clojure.core/some->" 1 "clojure.core/inc" 1
            "clojure.core/cond->" 1 "clojure.core/dec" 1}
           usages))))

(deftest refer-entries-are-not-usages
  (let [{:keys [usages requires]}
        (get (analyse-sources
              {"r.clj" "(ns r (:require [clojure.string :refer [trim upper-case]]))\n(trim \" x \")\n"})
             "r.clj")]
    (is (= {"clojure.string/trim" 1} usages))
    (is (= #{"clojure.string"} requires))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [7]: unresolved symbols became fake fqns, and build files
;; were analysed as code.

(deftest unresolved-symbols-are-counted-not-named
  (let [{:keys [usages unresolved]}
        (get (analyse-sources {"u.clj" "(ns u)\n(undefined-thing 1)\n(inc 1)\n"}) "u.clj")]
    (is (= {"clojure.core/inc" 1} usages))
    (is (= 1 unresolved))))

(deftest config-files-are-not-analysed
  (let [files (analyse-sources
               {"project.clj"     "(defproject foo \"0.1\" :dependencies [[lein-doo \"1\"]] :main foo.core)\n"
                "src/foo/core.clj" "(ns foo.core)\n(inc 1)\n"}
               {"project.clj" {:file/role :config}})]
    (is (= #{"src/foo/core.clj"} (set (keys files))))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [8]: an (:import ...) entry counted as a class usage.

(deftest imports-are-not-class-usages
  (let [{:keys [java]}
        (get (analyse-sources
              {"j.clj" (str "(ns j (:import (java.util HashMap ArrayDeque)))\n"
                            "(HashMap.) (java.util.TreeMap.) (Math/abs -1)\n")})
             "j.clj")]
    (is (= {"java.util.HashMap" 1 "java.util.TreeMap" 1 "java.lang.Math/abs" 1} java))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [3]: fully qualified calls to namespaces the file does not
;; require produced no usage at all.

(deftest unrequired-qualified-calls-are-counted
  (let [files (analyse-sources
               {"src/day1.clj" "(ns day1)\n(defn solve [] 1)\n"
                "src/day2.clj" (str "(ns day2)\n"
                                    "(clojure.set/union #{} #{})\n"
                                    "(clojure.set/union #{} #{})\n"
                                    "(map clojure.string/trim [\"a\"])\n"
                                    "(day1/solve)\n"
                                    "(str/trim \"x\")\n"
                                    "::str/kw\n"
                                    "#::m{:a 1}\n"
                                    "(comment (clojure.walk/keywordize-keys {}))\n")}
               {"src/day2.clj" {:file/comment-rows [[9 9]]}})
        f (get files "src/day2.clj")]
    (is (= 2 (get-in f [:usages "clojure.set/union"])))
    (is (= 1 (get-in f [:usages "clojure.string/trim"])))
    (testing "a single-segment namespace counts when the repo defines it"
      (is (= 1 (get-in f [:usages "day1/solve"]))))
    (testing "an alias nobody defined is unresolved; keywords and namespaced maps are not var references"
      (is (nil? (get-in f [:usages "str/trim"])))
      (is (= 1 (:unresolved f))))
    (testing "comment forms are still excluded"
      (is (nil? (get-in f [:usages "clojure.walk/keywordize-keys"]))))
    (is (= 4 (:unrequired-ns-usages f)))
    (testing "namespaces that are used but not required are not reported as requires"
      (is (= #{} (:requires f))))))

;; ---------------------------------------------------------------------------
;; datomic-and-kondo [9]: a kondo file older than its extract is redone.

(deftest stale-outputs-are-redone
  (let [dir     (tmp-dir)
        extract (str (fs/path dir "extract.edn"))
        kondo   (str (fs/path dir "kondo.edn"))
        t0      (java.nio.file.attribute.FileTime/fromMillis 1700000000000)
        t1      (java.nio.file.attribute.FileTime/fromMillis 1700000600000)]
    (try
      (spit extract "{}")
      (is (true? (k/stale? extract kondo)) "no kondo output yet")
      (spit kondo "{}")
      (fs/set-last-modified-time extract t0)
      (fs/set-last-modified-time kondo t1)
      (is (false? (k/stale? extract kondo)) "kondo output newer than the extract")
      (fs/set-last-modified-time extract t1)
      (fs/set-last-modified-time kondo t0)
      (is (true? (k/stale? extract kondo)) "extract re-run after kondo")
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; security [0]: a .clj-kondo dir above the JVM's working directory would let
;; clj-kondo run macro code from the analysed repos.

(deftest config-dir-guard
  (let [dir (tmp-dir)]
    (try
      (testing "a clean directory passes"
        (is (nil? (k/assert-no-kondo-config! dir nil))))
      (testing "CLJ_KONDO_EXTRA_CONFIG_DIR set: refused"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CLJ_KONDO_EXTRA_CONFIG_DIR"
                              (k/assert-no-kondo-config! dir "/somewhere"))))
      (testing "a .clj-kondo dir in a parent of the working directory: refused"
        (fs/create-dirs (fs/path dir ".clj-kondo"))
        (fs/create-dirs (fs/path dir "a" "b"))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.clj-kondo"
                              (k/assert-no-kondo-config! (str (fs/path dir "a" "b")) nil))))
      (testing "a .clj-kondo regular file: refused with the same clear error"
        (fs/delete-tree (fs/path dir ".clj-kondo"))
        (spit (str (fs/path dir ".clj-kondo")) "")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\.clj-kondo"
                              (k/assert-no-kondo-config! (str (fs/path dir "a")) nil))))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; The data contract: what we write must round-trip through clojure.edn.

(deftest output-round-trips-as-edn
  (let [files (analyse-sources {"a.clj" "(ns a (:require [clojure.string :as s]))\n(s/trim \" x \")\n(HashMap.)\n"})]
    (is (= files (edn/read-string (pr-str files))))
    (is (= #{:requires :usages :java :syntax-errors :analysed? :special-forms
             :unresolved :unrequired-ns-usages :comment-filtered?}
           (set (keys (get files "a.clj")))))))
