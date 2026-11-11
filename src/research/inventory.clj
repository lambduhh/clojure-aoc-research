(ns research.inventory
  "Stage 3a: one cloned repo -> an EDN summary of its files, repo-level
  signals, and declared dependencies. Runs under babashka.

  The repos are untrusted. Nothing here evaluates their code: EDN is read as
  data, project.clj is parsed with edamame (no eval), other build files are
  matched with regexes. Symlinks are skipped, files must stay inside the repo,
  and large files are ignored."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as e]
            [research.names :as names]
            [research.puzzle :as puzzle]))

(def max-file-bytes (* 1024 1024))

(def ext->lang
  {"clj" :clojure "cljc" :clojure "cljs" :clojure "bb" :clojure
   "ex" :elixir "exs" :elixir
   "ml" :ocaml
   "zig" :zig})

(def skip-dirs
  #{".git" "node_modules" "_build" "deps" "target" "zig-cache" ".zig-cache" "zig-out"
    "_opam" ".cpcache" ".shadow-cljs" "out" "vendor" ".elixir_ls" "cover"})

;; ---------------------------------------------------------------------------
;; Line counts

(defn- clojure-code-lines [lines]
  (count (remove #(let [t (str/trim %)] (or (str/blank? t) (str/starts-with? t ";"))) lines)))

(defn- elixir-code-lines [lines]
  (count (remove #(let [t (str/trim %)] (or (str/blank? t) (str/starts-with? t "#"))) lines)))

(defn- zig-code-lines [lines]
  (count (remove #(let [t (str/trim %)] (or (str/blank? t) (str/starts-with? t "//"))) lines)))

(defn strip-ocaml-comments
  "Remove (* ... *) comments, which nest in OCaml. Strings are not special-cased."
  [s]
  (let [sb (StringBuilder.) n (count s)]
    (loop [i 0 depth 0]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s i) nxt (when (< (inc i) n) (.charAt s (inc i)))]
          (cond
            (and (= c \() (= nxt \*)) (recur (+ i 2) (inc depth))
            (and (pos? depth) (= c \*) (= nxt \))) (recur (+ i 2) (dec depth))
            (pos? depth) (do (when (= c \newline) (.append sb \newline)) (recur (inc i) depth))
            :else (do (.append sb c) (recur (inc i) depth))))))))

(defn- ocaml-code-lines [text]
  (count (remove str/blank? (str/split-lines (strip-ocaml-comments text)))))

(defn comment-form-rows
  "Line numbers covered by top-level (comment ...) forms, or nil if the file
  doesn't parse."
  [text]
  (try
    (let [forms (e/parse-string-all text {:all true
                                          :auto-resolve (fn [_] 'user)
                                          :readers (fn [_] identity)
                                          :read-cond :allow
                                          :features #{:clj :cljs}})]
      (set (for [f forms
                 :when (and (seq? f) (= 'comment (first f)))
                 :let [{:keys [row end-row]} (meta f)]
                 :when (and row end-row)
                 r (range row (inc end-row))]
             r)))
    (catch Exception _ nil)))

(defn count-lines
  "{:file/lines :file/code-lines} plus, for Clojure,
  :file/code-lines-no-comment-forms (nil when the file doesn't parse)."
  [lang text]
  (let [lines (str/split-lines text)
        base  {:file/lines (count lines)
               :file/code-lines (case lang
                                  :clojure (clojure-code-lines lines)
                                  :elixir  (elixir-code-lines lines)
                                  :zig     (zig-code-lines lines)
                                  :ocaml   (ocaml-code-lines text))}]
    (if (= lang :clojure)
      (assoc base :file/code-lines-no-comment-forms
             (when-let [rows (comment-form-rows text)]
               (clojure-code-lines (keep-indexed (fn [i l] (when-not (rows (inc i)) l)) lines))))
      base)))

;; ---------------------------------------------------------------------------
;; Namespace / module names (for the puzzle mapper's :ns year source)

(defn module-name [lang text]
  (case lang
    :clojure (some->> (re-find #"\(ns\s+(?:\^\S+\s+)?([^\s()\[\]{}\"]+)" text) second)
    :elixir  (some->> (re-find #"defmodule\s+([A-Z][\w.]*)" text) second)
    nil))

(defn runtime [ext text]
  (cond
    (= ext "bb")                                   :bb
    (re-find #"^#!.*\bbb\b" text)                  :bb
    (re-find #"^#!.*\bnbb\b" text)                 :nbb
    (= ext "cljs")                                 :cljs
    (#{"clj" "cljc"} ext)                          :jvm
    :else                                          nil))

;; ---------------------------------------------------------------------------
;; Repo-level signals

(def build-files
  {"deps.edn" :deps-edn "project.clj" :project-clj "bb.edn" :bb-edn "shadow-cljs.edn" :shadow-cljs
   "build.boot" :boot "mix.exs" :mix-exs "dune-project" :dune-project "dune" :dune
   "build.zig" :build-zig "build.zig.zon" :build-zig-zon})

(def ai-files
  {"CLAUDE.md" :claude-md "AGENTS.md" :agents-md ".cursorrules" :cursorrules ".cursor" :cursor
   ".windsurfrules" :windsurfrules ".aider.conf.yml" :aider ".aiderignore" :aider
   ".github/copilot-instructions.md" :copilot-instructions ".claude" :claude-dir
   "GEMINI.md" :gemini-md ".continue" :continue})

(def editor-signals
  {".calva" :calva ".idea" :intellij ".dir-locals.el" :emacs ".vscode" :vscode ".lsp" :clojure-lsp
   ".clj-kondo" :clj-kondo ".nrepl-port" :nrepl-port ".projectile" :emacs ".vim" :vim
   ".elixir_ls" :elixir-ls ".zed" :zed})

(defn- present [repo-dir table]
  (into (sorted-set)
        (keep (fn [[rel kw]] (when (fs/exists? (fs/path repo-dir rel)) kw)))
        table))

(def ^:private java-version-files [".java-version" ".tool-versions" ".sdkmanrc"])

(defn java-version
  "First Java version declared by a version-manager file or a setup-java step."
  [repo-dir]
  (or (some (fn [f]
              (let [p (fs/path repo-dir f)]
                (when (fs/regular-file? p)
                  (let [s (slurp (str p))]
                    (some->> (or (re-find #"(?m)^java\s+\S*?(\d+(?:\.\d+)*)" s)
                                 (re-find #"(?m)^java=\S*?(\d+(?:\.\d+)*)" s)
                                 (re-find #"^\s*(\d+(?:\.\d+)*)" s))
                             second
                             (vector f))))))
            java-version-files)
      (let [wf (fs/path repo-dir ".github" "workflows")]
        (when (fs/directory? wf)
          (some (fn [p]
                  (when (fs/regular-file? p)
                    (some->> (re-find #"java-version:\s*['\"]?(\d+(?:\.\d+)*)" (slurp (str p)))
                             second
                             (vector ".github/workflows"))))
                (fs/list-dir wf))))))

;; ---------------------------------------------------------------------------
;; Declared dependencies

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

(defn- declarations [repo-dir rel-files]
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

;; ---------------------------------------------------------------------------
;; Templates: generated or copied-in day files that were never filled in

(defn fingerprint
  "Content with every digit run replaced by 0 and whitespace collapsed, then
  hashed. Templates that differ only by day number share a fingerprint."
  [text]
  (let [norm (-> text (str/replace #"\d+" "0") (str/replace #"\s+" " ") str/trim)
        md   (java.security.MessageDigest/getInstance "SHA-1")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String norm "UTF-8"))))))

(defn mark-templates
  "A file is a template when its fingerprint is shared by files mapped to two
  or more different puzzles in the same repo."
  [files]
  (let [puzzles-by-fp (reduce (fn [m {:file/keys [fingerprint] :puzzle/keys [keys]}]
                                (if (and fingerprint (seq keys)) (update m fingerprint into keys) m))
                              {} files)]
    (mapv (fn [{:file/keys [fingerprint] :as f}]
            (assoc f :file/template? (boolean (and fingerprint (seq (:puzzle/keys f))
                                                   (> (count (get puzzles-by-fp fingerprint)) 1)))))
          files)))

;; ---------------------------------------------------------------------------
;; Walking a repo

(defn- inside? [root p]
  (str/starts-with? (str (fs/canonicalize p)) (str (fs/canonicalize root))))

(defn repo-files
  "Relative paths of regular files, skipping vendored/build dirs and symlinks."
  [repo-dir]
  (let [root (fs/path repo-dir)]
    (->> (fs/glob root "**" {:hidden true :follow-links false})
         (filter #(and (fs/regular-file? % {:nofollow-links true})
                       (not (fs/sym-link? %))
                       (inside? root %)))
         (map #(str (fs/relativize root %)))
         (remove #(some skip-dirs (str/split % #"/")))
         sort)))

(defn summarize
  "repo (from data/repos.edn) + its clone dir -> EDN summary."
  [repo repo-dir]
  (let [rels      (repo-files repo-dir)
        repo-name (names/repo-name (:repo/full-name repo))
        sources   (for [rel rels
                        :let [ext (fs/extension rel) lang (ext->lang ext) p (fs/path repo-dir rel)]
                        :when lang
                        :let [size (fs/size p)]]
                    (if (> size max-file-bytes)
                      {:file/path rel :file/lang lang :file/status :too-large :file/bytes size}
                      (let [text (slurp (str p))
                            ns-name (module-name lang text)]
                        (merge {:file/path rel :file/lang lang :file/status :ok :file/bytes size
                                :file/runtime (runtime ext text) :file/module ns-name}
                               (count-lines lang text)
                               {:file/fingerprint (fingerprint text)}
                               (puzzle/map-file {:path rel :ns-name ns-name :repo-name repo-name
                                                 :created-at (:repo/created-at repo)})))))]
    {:repo/id             (:repo/id repo)
     :repo/lang           (:repo/lang repo)
     :repo/build-files    (into (sorted-set) (keep (comp build-files fs/file-name)) rels)
     :repo/ai-files       (present repo-dir ai-files)
     :repo/editor-signals (present repo-dir editor-signals)
     :repo/java-version   (java-version repo-dir)
     :repo/declarations   (declarations repo-dir rels)
     :repo/files          (mark-templates (vec sources))}))
