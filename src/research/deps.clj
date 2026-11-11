(ns research.deps
  "Declared dependencies from build files.

  The repos are untrusted. Nothing here evaluates their code: EDN is read as
  data; project.clj and build.boot are parsed with edamame, never evaluated;
  dune, dune-project, *.opam, mix.exs and build.zig.zon go through the small
  tokenizers below. Each tokenizer makes one forward pass and caps list
  nesting, so a crafted file costs time linear in its size, and deep nesting
  makes a file :unreadable instead of overflowing the stack. Every value
  returned is a plain string, keyword, boolean or nil, so the extract EDN
  reads back with clojure.edn."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as e]))

(def max-file-bytes (* 1024 1024))

;; ---------------------------------------------------------------------------
;; Values from untrusted files -> values that print as readable EDN

(defn- clean-name
  "s with every character outside a conservative keyword set replaced by _,
  cut to 100 characters; \"_\" if nothing is left."
  [s]
  (let [s (str/replace (subs s 0 (min 100 (count s))) #"[^A-Za-z0-9_.*+!?<>=-]" "_")]
    (if (= "" s) "_" s)))

(defn safe-keyword
  "A keyword built from x, a value read from an untrusted file, that prints as
  readable EDN. Keywords and symbols keep their namespace and name (cleaned);
  strings, numbers, booleans and characters use their text; anything else
  becomes :_."
  [x]
  (cond
    (or (keyword? x) (symbol? x))
    (if (namespace x)
      (keyword (clean-name (namespace x)) (clean-name (name x)))
      (keyword (clean-name (name x))))
    (or (string? x) (number? x) (boolean? x) (char? x)) (keyword (clean-name (str x)))
    :else :_))

(defn- string-val [m k] (let [v (get m k)] (when (string? v) v)))

(defn- dep [coord version scope local?]
  {:dep/coord coord :decl/version version :decl/scope scope :decl/local? (boolean local?)})

;; ---------------------------------------------------------------------------
;; Clojure: deps.edn, bb.edn, project.clj, shadow-cljs.edn, build.boot

(defn- unreadable-on-failure
  "(f), or ::unreadable if it throws an Exception or overflows the stack (the
  EDN reader and edamame recurse once per nesting level). Other errors, such
  as running out of memory, propagate."
  [f]
  (try (f)
       (catch Exception _ ::unreadable)
       ;; babashka can't name StackOverflowError, so catch its parent class
       ;; and rethrow anything else
       (catch VirtualMachineError t
         (if (= "java.lang.StackOverflowError" (.getName (class t))) ::unreadable (throw t)))))

(defn- read-edn-safely [text]
  (unreadable-on-failure #(edn/read-string {:default tagged-literal :readers {}} text)))

(def ^:private clj-parse-opts
  {:all true :read-eval false :auto-resolve (fn [_] 'user) :readers (fn [_] identity)})

(defn- parse-clj-forms
  "Top-level forms of a Clojure file, parsed with edamame (never evaluated),
  or ::unreadable."
  [text]
  (unreadable-on-failure #(e/parse-string-all text clj-parse-opts)))

(defn- coord-str [sym] (if (namespace sym) (str sym) (str sym "/" sym)))

(defn- short-sha [s] (when s (subs s 0 (min 7 (count s)))))

(defn- deps-edn-version
  "Version string for a deps.edn coordinate map, or nil. Only string values
  count, so a tagged literal or a number never reaches the output."
  [c]
  (or (string-val c :mvn/version)
      (string-val c :git/tag)
      (string-val c :tag)                 ; pre-2021 tools.deps git coordinate
      (short-sha (string-val c :git/sha))
      (short-sha (string-val c :sha))     ; pre-2021 tools.deps git coordinate
      (some->> (string-val c :local/root) (str "local:"))))

(defn- deps-edn-entries [scope deps]
  (for [[lib c] (when (map? deps) deps)
        :when (symbol? lib)
        :let [c (when (map? c) c)]]
    (dep (coord-str lib) (some-> c deps-edn-version) scope (some? (:local/root c)))))

(defn deps-edn-deps
  "deps.edn or bb.edn -> [{:dep/coord :decl/version :decl/scope :decl/local?}]:
  top-level :deps, plus each alias's :extra-deps, :replace-deps and :deps
  (a synonym for :replace-deps) scoped by the alias name. :override-deps and
  :default-deps are not read: they set versions for deps that something else
  brings in, and add none of their own. ::unreadable if the file isn't an
  EDN map."
  [text]
  (let [m (read-edn-safely text)]
    (if-not (map? m)
      ::unreadable
      (vec (concat (deps-edn-entries :main (:deps m))
                   (for [[alias a] (when (map? (:aliases m)) (:aliases m))
                         :when (map? a)
                         :let [scope (safe-keyword alias)]
                         k [:extra-deps :replace-deps :deps]
                         d (deps-edn-entries scope (get a k))]
                     d))))))

(defn- lein-dep
  "A Leiningen-style [lib \"version\" & opts] vector -> a dep, or nil."
  [scope v]
  (when (vector? v)
    (let [[lib version] v]
      (when (symbol? lib)
        (dep (coord-str lib) (when (string? version) version) scope false)))))

(defn- lein-deps [scope deps]
  (keep #(lein-dep scope %) (when (sequential? deps) deps)))

(defn- profile-scope
  "Scope keyword for a Leiningen profile name: :profile-<name>."
  [profile]
  (keyword (clean-name (str "profile-" (if (or (keyword? profile) (symbol? profile))
                                          (name profile)
                                          (str (when (or (string? profile) (number? profile)) profile)))))))

(defn project-clj-deps
  "project.clj -> deps from (defproject ...): :dependencies (scope :main),
  :plugins (scope :plugin), and each profile's :dependencies
  (:profile-<name>) and :plugins (:plugin). ::unreadable without a defproject."
  [text]
  (let [forms (parse-clj-forms text)
        proj  (when (sequential? forms)
                (first (filter #(and (seq? %) (= 'defproject (first %))) forms)))]
    (if-not proj
      ::unreadable
      (let [kvs (into {} (map vec) (partition 2 (drop 3 proj)))]
        (vec (concat (lein-deps :main (:dependencies kvs))
                     (lein-deps :plugin (:plugins kvs))
                     (for [[profile p] (when (map? (:profiles kvs)) (:profiles kvs))
                           :when (map? p)
                           d (concat (lein-deps (profile-scope profile) (:dependencies p))
                                     (lein-deps :plugin (:plugins p)))]
                       d)))))))

(defn shadow-cljs-deps
  "shadow-cljs.edn -> its :dependencies, which use Leiningen's vector shape.
  [] when the file defers to deps.edn (:deps true); ::unreadable if the file
  isn't an EDN map."
  [text]
  (let [m (read-edn-safely text)]
    (if (map? m) (vec (lein-deps :main (:dependencies m))) ::unreadable)))

(defn- dep-vectors
  "Leiningen-style [lib \"version\" ...] vectors anywhere in form, in order,
  without descending into them. Iterative, so nesting can't overflow."
  [form]
  (loop [stack [form] out []]
    (if-let [x (peek stack)]
      (let [stack (pop stack)]
        (cond
          (and (vector? x) (symbol? (first x)) (string? (second x))) (recur stack (conj out x))
          (map? x)  (recur (into stack (reverse (concat (keys x) (vals x)))) out)
          (coll? x) (recur (into stack (reverse x)) out)
          :else     (recur stack out)))
      out)))

(defn boot-deps
  "build.boot -> deps from every top-level (set-env! :dependencies ...). The
  value may be a quoted vector or a function such as #(into % '[...]); the
  dependency vectors are collected from it without evaluating anything."
  [text]
  (let [forms (parse-clj-forms text)]
    (if-not (sequential? forms)
      ::unreadable
      (vec (for [f forms
                 :when (and (seq? f) (= 'set-env! (first f)))
                 [k v] (partition 2 (rest f))
                 :when (= :dependencies k)
                 dv (dep-vectors v)
                 :let [d (lein-dep :main dv)]
                 :when d]
             d)))))

;; ---------------------------------------------------------------------------
;; Tokenizers for dune, dune-project, *.opam, mix.exs and build.zig.zon
;;
;; Each format has one token regex, run with re-seq, and a function that
;; turns each token string into a token for read-tree: a character opens a
;; list of that kind, :close closes one, :skip-datum and :none steer
;; read-tree, and anything else is a leaf ({:atom s}, {:str s}, :comma...).
;;
;; Why this is linear on crafted input: every repetition in the regexes is
;; possessive (*+ ++ ?+), so the engine doesn't backtrack into it, and a
;; possessive group didn't recurse per repetition when checked (500k escapes
;; in one string on a 256 KB thread stack under babashka). Every string or
;; comment alternative ends with |\z, so an unterminated one consumes the
;; rest of the input instead of failing and being rescanned from the next
;; position. re-seq also runs natively in babashka, several times faster
;; than a character loop written here; the slowest 1 MB input measured
;; (1M empty tuples in mix.exs) took about 1.4 s.

(def ^:private max-depth
  "Deepest list nesting a tokenized build file may have. Real files nest
  well under 20; anything deeper is ::unreadable."
  256)

(defn- read-tree
  "Top-level items from a seq of tokens. A character opens a list of that
  kind, which becomes {:list kind :items [...]}; :close closes the innermost
  list; :skip-datum drops the next item at its level (dune's #; comment);
  :none is ignored; anything else is a leaf. Unclosed lists are closed at the
  end and a stray :close is ignored. Iterative: past max-depth the result is
  ::unreadable rather than a stack overflow."
  [tokens]
  (loop [toks (seq tokens) items (transient []) skip 0 kind nil parents () depth 0]
    (if toks
      (let [tok (first toks) toks (next toks)]
        (cond
          (char? tok)         (if (< depth max-depth)
                                (recur toks (transient []) 0 tok (conj parents [items skip kind]) (inc depth))
                                ::unreadable)
          (= :close tok)      (if (pos? depth)
                                (let [[p-items p-skip p-kind] (first parents)
                                      node {:list kind :items (persistent! items)}]
                                  (if (pos? p-skip)
                                    (recur toks p-items (dec p-skip) p-kind (rest parents) (dec depth))
                                    (recur toks (conj! p-items node) p-skip p-kind (rest parents) (dec depth))))
                                (recur toks items skip kind parents depth))
          (= :none tok)       (recur toks items skip kind parents depth)
          (= :skip-datum tok) (recur toks items (inc skip) kind parents depth)
          (pos? skip)         (recur toks items (dec skip) kind parents depth)
          :else               (recur toks (conj! items tok) skip kind parents depth)))
      (loop [items items kind kind parents parents]
        (if-let [[p-items p-skip p-kind] (first parents)]
          (let [node {:list kind :items (persistent! items)}]
            (recur (if (pos? p-skip) p-items (conj! p-items node)) p-kind (rest parents)))
          (persistent! items))))))

(defn- quoted-body
  "The body of a token that opens with the string q, without the closing q
  if it has one. Escapes are left as written."
  [tok q]
  (let [n (count tok) k (count q)]
    (if (and (>= n (* 2 k)) (str/ends-with? tok q)) (subs tok k (- n k)) (subs tok k))))

(def ^:private sexp-token-re
  #"(?s)[()]|\"(?:[^\"\\]|\\.)*+(?:\"|\z)|;[^\n]*+|#\|[^|]*+(?:\|(?!#)[^|]*+)*+(?:\|#|\z)|#;|[^\s()\";]++")

(defn- sexp-token
  "Token for dune and dune-project files: parens, \"strings\" with escapes,
  ; line comments, #| block comments |#, #; datum comments, and atoms."
  [tok]
  (case (nth tok 0)
    \( \(
    \) :close
    \" {:str (quoted-body tok "\"")}
    \; :none
    \# (cond (str/starts-with? tok "#|") :none
             (= "#;" tok)                :skip-datum
             :else                       {:atom tok})
    {:atom tok}))

(defn- sexp-tree [text] (read-tree (map sexp-token (re-seq sexp-token-re text))))

(defn- head-atom [node] (when (:list node) (:atom (first (:items node)))))

(defn- stanzas "Lists in items whose head atom is h." [items h]
  (filter #(= h (head-atom %)) items))

(defn- first-flag
  "Scope of the first filter flag (looked up in flag->scope) among the atoms
  under nodes, in document order; nil if there is none. Flags under a dune
  (not ...) or after an opam ! are skipped. Iterative."
  [nodes flag->scope]
  (loop [stack (vec (reverse nodes))]
    (when-let [x (peek stack)]
      (let [stack (pop stack)]
        (cond
          (:atom x)          (or (flag->scope (:atom x)) (recur stack))
          (= "!" (:op x))    (recur (if (seq stack) (pop stack) stack))
          (and (:list x) (not= "not" (head-atom x))) (recur (into stack (reverse (:items x))))
          :else              (recur stack))))))

(def ^:private opam-flag-scope
  {"with-test" :test "with-doc" :doc "build" :build "with-dev-setup" :dev "post" :post})

(def ^:private dune-flag-scope
  (update-keys opam-flag-scope #(str ":" %)))

(defn- package-name? [s]
  (boolean (and s (re-matches #"[A-Za-z0-9_][A-Za-z0-9_+.-]*" s) (not (#{"and" "or" "not"} s)))))

(defn- library-name? [s]
  (boolean (and s (re-matches #"[A-Za-z_][A-Za-z0-9_.-]*" s))))

(defn- dune-project-analysis
  "dune-project text -> {:deps [...] :defines #{package names}}, or
  ::unreadable. Reads (depends ...) in every (package ...): the head atom of
  each item is the package, and the first :with-test / :with-doc / :build /
  :with-dev-setup / :post flag in its constraint is the scope."
  [text]
  (let [top (sexp-tree text)]
    (if (= ::unreadable top)
      ::unreadable
      (let [pkgs (stanzas top "package")]
        {:defines (set (keep #(-> (stanzas (:items %) "name") first :items second :atom) pkgs))
         :deps    (vec (distinct
                        (for [pkg pkgs
                              ds  (stanzas (:items pkg) "depends")
                              item (rest (:items ds))
                              :let [[nm scope] (cond (:atom item) [(:atom item) nil]
                                                     (:list item) [(head-atom item)
                                                                   (first-flag (rest (:items item)) dune-flag-scope)])]
                              :when (package-name? nm)]
                          (dep nm nil (or scope :main) false))))}))))

(defn dune-project-deps
  "dune-project -> the packages each (package ...) (depends ...)s on."
  [text]
  (let [a (dune-project-analysis text)] (if (= ::unreadable a) a (:deps a))))

(def ^:private dune-library-stanzas
  {"library" :main "executable" :main "executables" :main "test" :test "tests" :test})

(defn- dune-analysis
  "dune text -> {:deps [...] :defines #{library names}}, or ::unreadable.
  Reads (libraries ...) in library, executable(s) and test(s) stanzas, also
  inside (subdir ...); test stanzas get scope :test. (re_export lib) counts
  as lib. (select ...) is skipped: it picks between implementations
  depending on which optional libraries are installed, so none of its
  choices is a firm dependency. Defines are the (name ...) and
  (public_name ...) of library stanzas."
  [text]
  (let [top (sexp-tree text)]
    (if (= ::unreadable top)
      ::unreadable
      (let [all (loop [todo (vec (reverse top)) out []]
                  (if-let [x (peek todo)]
                    (let [todo (pop todo)]
                      (if (= "subdir" (head-atom x))
                        (recur (into todo (reverse (drop 2 (:items x)))) out)
                        (recur todo (if (dune-library-stanzas (head-atom x)) (conj out x) out))))
                    out))
            field (fn [st k] (keep #(-> % :items second :atom) (stanzas (:items st) k)))]
        {:defines (set (for [st all :when (= "library" (head-atom st))
                             nm (concat (field st "name") (field st "public_name"))]
                         nm))
         :deps    (vec (distinct
                        (for [st all
                              :let [scope (dune-library-stanzas (head-atom st))]
                              libs (stanzas (:items st) "libraries")
                              item (rest (:items libs))
                              :let [nm (cond (:atom item) (:atom item)
                                             (= "re_export" (head-atom item)) (-> item :items second :atom))]
                              :when (library-name? nm)]
                          (dep nm nil scope false))))}))))

(defn dune-deps
  "dune -> the libraries its stanzas list in (libraries ...)."
  [text]
  (let [a (dune-analysis text)] (if (= ::unreadable a) a (:deps a))))

(def ^:private opam-token-re
  #"(?s)\(\*[^*]*+(?:\*(?!\))[^*]*+)*+(?:\*\)|\z)|[\[\]{}()]|\"\"\"(?:[^\"]|\"(?!\"\"))*+(?:\"\"\"|\z)|\"(?:[^\"\\]|\\.)*+(?:\"|\z)|#[^\n]*+|!=|[<>]=?|[=!&|:]|[^\s\[\]{}()\"#!=<>&|:]++")

(defn- opam-token
  "Token for opam files: [ ] { } ( ) brackets, \"strings\" and
  \"\"\"strings\"\"\", # line comments, (* block comments *), operators
  ({:op s}) and identifiers ({:atom s})."
  [tok]
  (let [c (nth tok 0)]
    (case c
      \(               (if (str/starts-with? tok "(*") :none \()
      (\[ \{)          c
      (\] \} \))       :close
      \"               {:str (if (str/starts-with? tok "\"\"\"") (quoted-body tok "\"\"\"") (quoted-body tok "\""))}
      \#               :none
      (\! \= \< \> \& \| \:) {:op tok}
      {:atom tok})))

(defn- opam-packages
  "[[name scope-or-nil] ...] for the items of an opam depends list (or of a
  (group) inside one). A {filter} after a package or a group gives the first
  flag's scope to each package that an inner filter didn't already scope.
  Groups nest at most 16 deep; deeper ones are ignored (real files use 1)."
  [items depth]
  (loop [xs items prev [] out []]
    (if-let [x (first xs)]
      (cond
        (:str x)          (recur (rest xs) [[(:str x) nil]] (into out prev))
        (= \( (:list x))  (recur (rest xs) (if (< depth 16) (opam-packages (:items x) (inc depth)) []) (into out prev))
        (= \{ (:list x))  (let [sc (first-flag (:items x) opam-flag-scope)]
                            (recur (rest xs) [] (into out (map (fn [[nm s]] [nm (or s sc)])) prev)))
        :else             (recur (rest xs) prev out))
      (into out prev))))

(defn opam-deps
  "*.opam -> packages in the top-level depends: [...] field. The first
  with-test / with-doc / build / with-dev-setup / post flag in a package's
  {filter} is its scope; a flag after ! doesn't count. depopts are not read."
  [text]
  (let [top (read-tree (map opam-token (re-seq opam-token-re text)))]
    (if (= ::unreadable top)
      ::unreadable
      (let [lst (some (fn [[a b c]] (when (and (= "depends" (:atom a)) (= ":" (:op b)) (= \[ (:list c))) c))
                      (partition 3 1 top))]
        (vec (distinct (for [[nm scope] (opam-packages (:items lst) 0)
                             :when (package-name? nm)]
                         (dep nm nil (or scope :main) false))))))))

(def ^:private elixir-token-re
  ;; Alternatives in order: comment, heredocs, string (one level of #{...}
  ;; holding braces-free code and simple strings; any other # is plain text),
  ;; charlist, sigil with each delimiter, quoted atom, atom, map opener,
  ;; brackets, comma, identifier or keyword key (only:), char literal,
  ;; number, a run of operator characters, any other non-space character.
  (re-pattern
   (str "(?s)"
        (str/join "|" (map str [#"#[^\n]*+"
                                #"\"\"\"(?:[^\"]|\"(?!\"\"))*+(?:\"\"\"|\z)"
                                #"'''(?:[^']|'(?!''))*+(?:'''|\z)"
                                #"\"(?:[^\"\\#]|\\.|#\{(?:[^{}\"\\]|\\.|\"(?:[^\"\\]|\\.)*+\")*+\}|#)*+(?:\"|\z)"
                                #"'(?:[^'\\]|\\.)*+(?:'|\z)"
                                #"~[A-Za-z]++(?:\"\"\"(?:[^\"]|\"(?!\"\"))*+(?:\"\"\"|\z)|'''(?:[^']|'(?!''))*+(?:'''|\z)|\((?:[^)\\]|\\.)*+(?:\)|\z)|\[(?:[^\]\\]|\\.)*+(?:\]|\z)|\{(?:[^}\\]|\\.)*+(?:\}|\z)|<(?:[^>\\]|\\.)*+(?:>|\z)|/(?:[^/\\]|\\.)*+(?:/|\z)|\|(?:[^|\\]|\\.)*+(?:\||\z)|\"(?:[^\"\\]|\\.)*+(?:\"|\z)|'(?:[^'\\]|\\.)*+(?:'|\z))?+"
                                #":\"(?:[^\"\\]|\\.)*+(?:\"|\z)"
                                #":[A-Za-z_][A-Za-z0-9_@]*+[?!]?+"
                                #"%\{"
                                #"[{}\[\]()]"
                                #","
                                #"[A-Za-z_][A-Za-z0-9_]*+[?!]?+(?::(?=\s))?+"
                                #"\?\\?."
                                #"[0-9][0-9A-Za-z_.]*+"
                                #"[^\s\"'#~:%{}\[\](),A-Za-z0-9_?]++"
                                #"\S"])))))

(def ^:private ident-start (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_"))

(defn- elixir-token
  "Token for mix.exs. Strings, charlists, heredocs, sigils and comments are
  single tokens, so brackets inside them don't count. Leaves are {:atom s},
  {:kw s} (a keyword key such as only:), {:str s}, {:ident s}, :comma and
  :other; %{ opens a list of kind \\%."
  [tok]
  (let [c (nth tok 0) n (count tok)]
    (case c
      \#         :none
      \"         (if (str/starts-with? tok "\"\"\"") :other {:str (quoted-body tok "\"")})
      (\' \~)    :other
      \:         (cond (= 1 n)              :other
                       (= \" (nth tok 1))   {:atom (quoted-body (subs tok 1) "\"")}
                       :else                {:atom (subs tok 1)})
      \%         (if (= "%{" tok) \% :other)
      (\{ \[ \() c
      (\} \] \)) :close
      \,         :comma
      (cond (not (ident-start c))     :other
            (str/ends-with? tok ":")  {:kw (subs tok 0 (dec n))}
            :else                     {:ident tok}))))

(defn- all-lists
  "Every list node under items, parents before children, in document order.
  Iterative."
  [items]
  (loop [stack (vec (reverse items)) out []]
    (if-let [x (peek stack)]
      (let [stack (pop stack)]
        (if (:list x)
          (recur (into stack (reverse (:items x))) (conj out x))
          (recur stack out)))
      out)))

(def ^:private mix-dep-keys
  "Options that only appear in a dependency tuple. A {:name, opts} tuple
  without a version string counts as a dependency when it has one of them."
  #{"git" "github" "path" "in_umbrella" "hex" "only" "runtime" "override" "optional"})

(defn- mix-scope
  "Scope from an only: value. A list containing :dev is :dev, else one
  containing :test is :test, else its first environment."
  [only]
  (let [envs (cond (:atom only) [(:atom only)]
                   (= \[ (:list only)) (keep :atom (:items only)))]
    (cond (empty? envs)           :main
          (some #{"dev"} envs)    :dev
          (some #{"test"} envs)   :test
          :else                   (safe-keyword (first envs)))))

(defn- mix-dep
  "A {:name, \"version\", opts...} or {:name, opts...} tuple -> a dep, or nil.
  hex: names the published package, so it is the coord when present. path:
  and in_umbrella: deps are local; a path dep's version is local:<path>."
  [{:keys [items]}]
  (let [[[name-tok & extra] & more] (remove #(= :comma (first %)) (partition-by #{:comma} items))
        version (:str (first (first more)))
        opts    (into {} (for [[k v] more :when (:kw k)] [(:kw k) v]))]
    (when (and (:atom name-tok) (empty? extra)
               (or version (some mix-dep-keys (keys opts))))
      (let [hex  (get opts "hex")
            path (:str (get opts "path"))]
        (dep (or (:atom hex) (:str hex) (:atom name-tok))
             (or version (:str (get opts "tag")) (:str (get opts "ref")) (some->> path (str "local:")))
             (mix-scope (get opts "only"))
             (or (contains? opts "path") (contains? opts "in_umbrella")))))))

(defn mix-exs-deps
  "mix.exs -> deps from {:name, ...} tuples anywhere in the file, outside
  comments and strings. only: sets the scope (see mix-scope)."
  [text]
  (let [top (read-tree (map elixir-token (re-seq elixir-token-re text)))]
    (if (= ::unreadable top)
      ::unreadable
      (vec (keep #(when (= \{ (:list %)) (mix-dep %)) (all-lists top))))))

(def ^:private zon-token-re
  ;; Alternatives in order: // comment, \\ multiline-string line, .{ or {, },
  ;; .@"field", .field, string, =, comma, a run of other characters, and a
  ;; single . / or \ that didn't start anything above.
  #"(?s)//[^\n]*+|\\\\[^\n]*+|\.?\{|\}|\.@\"(?:[^\"\\]|\\.)*+(?:\"|\z)|\.[A-Za-z_][A-Za-z0-9_]*+|\"(?:[^\"\\]|\\.)*+(?:\"|\z)|=|,|[^\s{}\".=,/\\]++|[./\\]")

(defn- zon-token
  "Token for build.zig.zon: // comments, \\\\ multiline-string lines, .{ and
  { braces, .field and .@\"field\" names, strings, = and ,. Leaves are
  {:field s}, {:str s}, :eq, :comma and :other."
  [tok]
  (case (nth tok 0)
    \/ (if (str/starts-with? tok "//") :none :other)
    \{ \{
    \} :close
    \. (cond (= ".{" tok)                    \{
             (str/starts-with? tok ".@\"")   {:field (quoted-body (subs tok 2) "\"")}
             (> (count tok) 1)               {:field (subs tok 1)}
             :else                           :other)
    \" {:str (quoted-body tok "\"")}
    \= :eq
    \, :comma
    :other))

(defn- field-values
  "[[name value] ...] for each .name = value directly in items."
  [items]
  (keep (fn [[a b c]] (when (and (:field a) (= :eq b)) [(:field a) c])) (partition 3 1 items)))

(defn zon-deps
  "build.zig.zon -> the entries of the top-level .dependencies = .{...}
  block, bounded by brace matching. A .path dep is local, with version
  local:<path>; .url deps have no version."
  [text]
  (let [top (read-tree (map zon-token (re-seq zon-token-re text)))]
    (if (= ::unreadable top)
      ::unreadable
      (let [root  (first (filter :list top))
            block (some (fn [[k v]] (when (and (= "dependencies" k) (:list v)) v)) (field-values (:items root)))]
        (vec (for [[nm entry] (field-values (:items block))
                   :when (:list entry)
                   :let [fields (into {} (field-values (:items entry)))]]
               (dep nm (some->> (:str (get fields "path")) (str "local:")) :main (contains? fields "path"))))))))

;; ---------------------------------------------------------------------------
;; One repo

(defn- deps-only
  "Wraps a text -> deps parser so it returns {:deps deps} like the OCaml
  analyses do."
  [f]
  (fn [text] (let [r (f text)] (if (= ::unreadable r) r {:deps r}))))

(defn- build-file-type
  "How to read a build file, from its file name. :ecosystem and :kind come
  from the file type, not from the repo's language."
  [file-name]
  (cond
    (#{"deps.edn" "bb.edn"} file-name)   {:parse (deps-only deps-edn-deps) :ecosystem :maven :kind :package}
    (= "project.clj" file-name)          {:parse (deps-only project-clj-deps) :ecosystem :maven :kind :package}
    (= "shadow-cljs.edn" file-name)      {:parse (deps-only shadow-cljs-deps) :ecosystem :maven :kind :package}
    (= "build.boot" file-name)           {:parse (deps-only boot-deps) :ecosystem :maven :kind :package}
    (= "mix.exs" file-name)              {:parse (deps-only mix-exs-deps) :ecosystem :hex :kind :package}
    (= "build.zig.zon" file-name)        {:parse (deps-only zon-deps) :ecosystem :zig :kind :package}
    (= "dune-project" file-name)         {:parse dune-project-analysis :ecosystem :opam :kind :package}
    (= "dune" file-name)                 {:parse dune-analysis :ecosystem :opam :kind :library}
    (and (str/ends-with? file-name ".opam") (< 5 (count file-name)))
    {:parse (deps-only opam-deps) :ecosystem :opam :kind :package
     ;; an opam file's name is the package it describes
     :defines #{(subs file-name 0 (- (count file-name) 5))}}))

(defn- readable-build-file?
  "A regular file, not a symlink, inside repo-dir and under max-file-bytes."
  [repo-dir p]
  (and (not (fs/sym-link? p))
       (fs/regular-file? p {:nofollow-links true})
       (str/starts-with? (str (fs/canonicalize p)) (str (fs/canonicalize repo-dir) "/"))
       (<= (fs/size p) max-file-bytes)))

(defn declarations
  "Every build file among rel-files -> {:decl/source :decl/status
  :decl/ecosystem :decl/kind :decl/deps}. Symlinks, files outside repo-dir
  and files over max-file-bytes are skipped. A file whose parser throws an
  Exception or overflows the stack is :unreadable with no deps, so one bad
  build file doesn't fail the repo. OCaml deps on a library or package the
  repo defines itself (dune name/public_name, dune-project package name,
  *.opam file name) are marked :decl/local?."
  [repo-dir rel-files]
  (let [parsed (vec (for [rel rel-files
                          :let [t (build-file-type (str (fs/file-name rel)))]
                          :when t
                          :let [p (fs/path repo-dir rel)]
                          :when (readable-build-file? repo-dir p)]
                      (let [r (unreadable-on-failure #((:parse t) (slurp (str p))))
                            ok? (not= ::unreadable r)]
                        {:defines (into (set (:defines t)) (when ok? (:defines r)))
                         :decl {:decl/source (str rel)
                                :decl/status (if ok? :ok :unreadable)
                                :decl/ecosystem (:ecosystem t)
                                :decl/kind (:kind t)
                                :decl/deps (if ok? (vec (:deps r)) [])}})))
        local (into #{} (comp (filter #(= :opam (get-in % [:decl :decl/ecosystem]))) (mapcat :defines)) parsed)]
    (mapv (fn [{:keys [decl]}]
            (if (= :opam (:decl/ecosystem decl))
              (update decl :decl/deps (partial mapv #(cond-> % (local (:dep/coord %)) (assoc :decl/local? true))))
              decl))
          parsed)))
