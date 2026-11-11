(ns research.deps
  "Declared dependencies from build files. The repos are untrusted:
  EDN is read as data, project.clj is parsed with edamame (never
  evaluated), other build files are matched as text."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as e]))

(def max-file-bytes (* 1024 1024))

(defn- read-edn-safely [text]
  (try (edn/read-string {:default tagged-literal :readers {}} text) (catch Exception _ ::unreadable)))

(defn- coord-str [sym] (if (namespace sym) (str sym) (str sym "/" sym)))

(defn deps-edn-deps
  "deps.edn or bb.edn -> [{:dep/coord :decl/version :decl/scope}], top level
  :deps plus every alias's :extra-deps / :replace-deps."
  [text]
  (let [m (read-edn-safely text)]
    (if-not (map? m)
      ::unreadable
      (let [version (fn [c] (when (map? c) (or (:mvn/version c) (:git/tag c) (some-> (:git/sha c) (subs 0 (min 7 (count (:git/sha c))))) (some-> (:local/root c) (str "local:")))))
            entries (fn [scope deps]
                      (for [[lib c] (when (map? deps) deps)
                            :when (symbol? lib)]
                        {:dep/coord (coord-str lib) :decl/version (version c) :decl/scope scope}))]
        (vec (concat (entries :main (:deps m))
                     (for [[alias a] (when (map? (:aliases m)) (:aliases m))
                           :when (map? a)
                           dep (concat (entries alias (:extra-deps a))
                                       (entries alias (:replace-deps a)))]
                       dep)))))))

(defn project-clj-deps
  "project.clj -> deps from (defproject ... :dependencies [...] :profiles {...}).
  Parsed with edamame, never evaluated."
  [text]
  (try
    (let [forms (e/parse-string-all text {:all true :read-eval false
                                          :auto-resolve (fn [_] 'user)
                                          :readers (fn [_] identity)})
          proj  (first (filter #(and (seq? %) (= 'defproject (first %))) forms))
          kvs   (when proj (apply hash-map (drop 3 (take (+ 3 (* 2 (quot (- (count proj) 3) 2))) proj))))
          dep   (fn [scope [lib v & _]]
                  (when (symbol? lib)
                    {:dep/coord (coord-str lib) :decl/version (when (string? v) v) :decl/scope scope}))]
      (if-not proj
        ::unreadable
        (vec (concat (keep #(when (vector? %) (dep :main %)) (:dependencies kvs))
                     (for [[profile p] (when (map? (:profiles kvs)) (:profiles kvs))
                           :when (map? p)
                           d (:dependencies p)
                           :when (vector? d)
                           :let [x (dep (keyword (str "profile-" (name profile))) d)]
                           :when x]
                       x)))))
    (catch Exception _ ::unreadable)))

(defn strip-elixir-comment
  "Drop a # comment from one line: a # outside a string that doesn't start
  string interpolation (#{)."
  [line]
  (let [n (count line)]
    (loop [i 0 in-str? false]
      (if (>= i n)
        line
        (let [c (.charAt ^String line i)]
          (cond
            (and in-str? (= c \\))                          (recur (+ i 2) in-str?)
            (= c \")                                        (recur (inc i) (not in-str?))
            (and (not in-str?) (= c \#)
                 (not= \{ (when (< (inc i) n) (.charAt ^String line (inc i))))) (subs line 0 i)
            :else                                           (recur (inc i) in-str?)))))))

(defn mix-exs-deps [text]
  (let [code (str/join "\n" (map strip-elixir-comment (str/split-lines text)))]
    (vec (for [[_ dep version] (re-seq #"\{\s*:(\w+)\s*,\s*\"([^\"]*)\"" code)]
           {:dep/coord dep :decl/version version :decl/scope :main}))))

(defn zon-deps [text]
  (let [block (second (re-find #"(?s)\.dependencies\s*=\s*\.\{(.*)" text))]
    (vec (for [[_ dep] (re-seq #"\.@?\"?([A-Za-z_][\w-]*)\"?\s*=\s*\.\{" (or block ""))]
           {:dep/coord dep :decl/version nil :decl/scope :main}))))

(defn opam-deps [text]
  (let [block (second (re-find #"(?s)depends:\s*\[(.*?)\]" text))]
    (vec (for [[_ dep] (re-seq #"\"([a-z][\w-]*)\"" (or block ""))]
           {:dep/coord dep :decl/version nil :decl/scope :main}))))

(defn dune-project-deps [text]
  (let [block (second (re-find #"(?s)\(depends(.*?)\)\s*\)" text))]
    (vec (for [[_ dep] (re-seq #"\(?\s*([a-z][\w-]*)" (or block ""))
               :when (not (#{"and" "or" "with-test" "with-doc" ">=" "<=" "=" ">" "<"} dep))]
           {:dep/coord dep :decl/version nil :decl/scope :main}))))

(defn declarations [repo-dir rel-files]
  (vec (for [rel rel-files
             :let [file-name (fs/file-name rel)
                   parser (cond
                            (#{"deps.edn" "bb.edn"} file-name) deps-edn-deps
                            (= "project.clj" file-name)        project-clj-deps
                            (= "mix.exs" file-name)            mix-exs-deps
                            (= "build.zig.zon" file-name)      zon-deps
                            (= "dune-project" file-name)       dune-project-deps
                            (str/ends-with? file-name ".opam") opam-deps)]
             :when parser
             :let [p (fs/path repo-dir rel)]
             :when (and (fs/regular-file? p) (<= (fs/size p) max-file-bytes))
             :let [result (parser (slurp (str p)))]]
         (if (= result ::unreadable)
           {:decl/source rel :decl/status :unreadable}
           {:decl/source rel :decl/status :ok :decl/deps result}))))

