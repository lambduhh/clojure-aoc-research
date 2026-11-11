(ns research.inventory
  "Stage 3a: one cloned repo -> an EDN summary of its files, repo-level
  signals, and declared dependencies. Runs under babashka.

  The repos are untrusted. Nothing here evaluates their code: Clojure is
  parsed with edamame (no eval), build files are handled by research.deps,
  and everything else is matched with regexes. Symlinks are skipped. The
  clone holds them as plain files (core.symlinks=false), so they're found in
  its git index, read with hooks and helpers disabled. Files must stay inside
  the repo, and large files are ignored."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [edamame.core :as e]
            [research.deps :as deps]
            [research.names :as names]
            [research.puzzle :as puzzle]))

(def max-file-bytes (* 1024 1024))

(def ext->lang
  {"clj" :clojure "cljc" :clojure "cljs" :clojure "bb" :clojure
   "ex" :elixir "exs" :elixir "livemd" :elixir
   "ml" :ocaml
   "zig" :zig})

(def skip-dirs
  #{".git" "node_modules" "_build" "deps" "target" "zig-cache" ".zig-cache" "zig-out"
    "_opam" ".cpcache" ".shadow-cljs" "out" "vendor" ".elixir_ls" "cover"})

;; ---------------------------------------------------------------------------
;; Line counts
;;
;; A lexer per language finds comments, doc comments and string literals as
;; [start end kind] spans by jumping from one regex match to the next. (A loop
;; over every character is far too slow under babashka's interpreter.)
;; Structure passes then find documentation (Clojure docstrings, Elixir @doc)
;; and inline tests as [start end] character ranges. A line counts as
;;   code  if any non-whitespace character on it is code (string literals are code),
;;   test  else if any is inside an inline test,
;;   doc   else if any is documentation,
;; and otherwise it is blank or a comment, and isn't counted.
;;
;; The passes work on two copies of the file with the same length, so offsets
;; carry over. "masked" blanks comments and marks documentation and tests with
;; private-use characters; "bare" also replaces string contents, so keywords
;; and brackets can be found in code only.

(def ^:private doc-mark  "\uE001")
(def ^:private test-mark "\uE002")
(def ^:private str-mark  "\uE003")

(defn- char-at [^String s i]
  (when (< -1 i (.length s)) (.charAt s (int i))))

(defn- eol
  "Index of the newline that ends the line holding i, or the end of s."
  [^String s i]
  (let [j (.indexOf s "\n" (int i))] (if (neg? j) (.length s) j)))

(defn- find-at
  "Start of the first match of matcher m at or after i, or nil. The match
  itself stays readable on m."
  [^java.util.regex.Matcher m ^String s i]
  (when (and (<= i (.length s)) (.find m (int i))) (.start m)))

(def ^:private literal-pattern
  (memoize
   (fn [close single-line? interp?]
     (re-pattern (str "\\\\|" (java.util.regex.Pattern/quote close)
                      (when single-line? "|\\n")
                      (when interp? "|#\\{"))))))

(declare ^:private skip-interpolation)

(defn- literal-end
  "Index just past the `close` that ends a literal whose body starts at i. A
  backslash escapes the next character. With :single-line? an unterminated
  literal ends at the newline; with :interp? Elixir #{...} is skipped."
  [^String s i close {:keys [single-line? interp?]}]
  (let [n (.length s)
        m (re-matcher (literal-pattern close (boolean single-line?) (boolean interp?)) s)]
    (loop [i i]
      (if-let [j (find-at m s i)]
        (let [c (.charAt s (int j))]
          (cond
            (= c \\)                (recur (min n (+ j 2)))
            (= c \newline)          j
            (and interp? (= c \#))  (recur (skip-interpolation s (+ j 2)))
            :else                   (+ j (count close))))
        n))))

(defn- skip-interpolation
  "Index just past the } that closes an Elixir #{ whose body starts at i."
  [^String s i]
  (let [m (re-matcher #"[{}\"']" s)]
    (loop [i i depth 0]
      (if-let [j (find-at m s i)]
        (case (.charAt s (int j))
          \{ (recur (inc j) (inc depth))
          \} (if (zero? depth) (inc j) (recur (inc j) (dec depth)))
          \" (recur (literal-end s (inc j) "\"" {:interp? true}) depth)
          \' (recur (literal-end s (inc j) "'" {:interp? true}) depth))
        (.length s)))))

;; --- lexers: text -> [[start end kind]], kind :comment, :doc or :string

(defn- lex-clojure
  "; and #! comments, and string and regex literals. A character literal like
  \\\" or \\; is skipped so it can't open a string or a comment."
  [^String s]
  (let [m (re-matcher #";|#!|#?\"|\\" s)]
    (loop [i 0 spans []]
      (if-let [j (find-at m s i)]
        (let [tok (.group m)]
          (cond
            (#{";" "#!"} tok) (let [e (eol s j)] (recur e (conj spans [j e :comment])))
            (= tok "\\")      (recur (+ j 2) spans)
            :else             (let [e (literal-end s (.end m) "\"" {})] (recur e (conj spans [j e :string])))))
        spans))))

(defn- lex-zig
  "// comments (/// and //! are doc comments, //// is not), \\\\ multiline
  string lines, and string and character literals, which can't span lines."
  [^String s]
  (let [m (re-matcher #"//|\\\\|\"|'" s)]
    (loop [i 0 spans []]
      (if-let [j (find-at m s i)]
        (let [tok (.group m) e (eol s j)]
          (case tok
            "//"   (let [c2 (char-at s (+ j 2))
                         doc? (or (= c2 \!) (and (= c2 \/) (not= (char-at s (+ j 3)) \/)))]
                     (recur e (conj spans [j e (if doc? :doc :comment)])))
            "\\\\" (recur e (conj spans [j e :string]))
            (let [e (literal-end s (inc j) tok {:single-line? true})]
              (recur e (conj spans [j e :string])))))
        spans))))

(def ^:private ocaml-char-literal
  #"'(?:[^\\'\n]|\\(?:[\\\"'ntbr ]|[0-9]{3}|x[0-9a-fA-F]{2}|o[0-7]{3}|u\{[0-9a-fA-F]+\}))'")

;; {id| or a quoted extension {%ext| / {%ext id| / {%%ext|; group 1 is id
(def ^:private ocaml-quoted-open "\\{(?:%%?[A-Za-z_][\\w.']*\\s*)?([a-z_]*)\\|")

(def ^:private ocaml-token
  (re-pattern (str "\\(\\*|\"|" ocaml-quoted-open "|'")))

(def ^:private ocaml-comment-token     ; '"' and '\"' are quote characters
  (re-pattern (str "\\(\\*|\\*\\)|\"|" ocaml-quoted-open "|'\\\\?\"'")))

(defn- quoted-string-end
  "Index just past the |id} that closes an OCaml {id| quoted string whose body starts at i."
  [^String s i id]
  (let [close (str "|" id "}") j (.indexOf s close (int i))]
    (if (neg? j) (.length s) (+ j (count close)))))

(defn- ocaml-comment-end
  "Index just past the *) that closes a comment whose body starts at i.
  Comments nest, and string literals inside them are skipped, as OCaml's own
  lexer does: (* \"*)\" *) is one comment."
  [^String s i]
  (let [m (re-matcher ocaml-comment-token s)]
    (loop [i i depth 1]
      (if (find-at m s i)
        (let [tok (.group m) e (.end m)]
          (cond
            (= tok "(*")               (recur e (inc depth))
            (= tok "*)")               (if (= 1 depth) e (recur e (dec depth)))
            (= tok "\"")               (recur (literal-end s e "\"" {}) depth)
            (str/starts-with? tok "{") (recur (quoted-string-end s e (.group m 1)) depth)
            :else                      (recur e depth)))
        (.length s)))))

(defn- lex-ocaml
  "(* *) comments, which nest ((** *) is a doc comment; (**) and (*** are
  not), \"...\" strings, {id|...|id} and {%ext|...|} quoted strings, and character literals
  such as '\"'. A quote after a name character is part of the name (x') and
  one that doesn't form a literal is a type variable ('a)."
  [^String s]
  (let [m  (re-matcher ocaml-token s)
        cm (re-matcher ocaml-char-literal s)
        n  (.length s)]
    (loop [i 0 spans []]
      (if-let [j (find-at m s i)]
        (let [tok (.group m) e (.end m)]
          (cond
            (= tok "(*")
            (let [doc? (and (= \* (char-at s (+ j 2))) (not (#{\* \)} (char-at s (+ j 3)))))
                  end  (ocaml-comment-end s e)]
              (recur end (conj spans [j end (if doc? :doc :comment)])))

            (= tok "\"")
            (let [end (literal-end s e "\"" {})] (recur end (conj spans [j end :string])))

            (str/starts-with? tok "{")
            (let [end (quoted-string-end s e (.group m 1))] (recur end (conj spans [j end :string])))

            (some->> (char-at s (dec j)) str (re-matches #"[\w']"))
            (recur e spans)

            :else
            (do (.region cm (int j) (int n))
                (if (.lookingAt cm)
                  (let [end (.end cm)] (recur end (conj spans [j end :string])))
                  (recur e spans)))))
        spans))))

(def ^:private elixir-token
  #"#|\"\"\"|'''|\"|'|\?|~(?:[a-z]|[A-Z][A-Z0-9]*)(?:\"\"\"|'''|[/|\"'(\[{<])")

(def ^:private heredoc-close {"\"\"\"" #"(?m)^[ \t]*\"\"\"" "'''" #"(?m)^[ \t]*'''"})

(def ^:private sigil-close {"(" ")" "[" "]" "{" "}" "<" ">"})

(defn- heredoc-end
  "Index just past the closing delimiter of a heredoc opened just before i.
  The delimiter closes it only at the start of a later line (after indentation)."
  [^String s i delim]
  (let [m (re-matcher (heredoc-close delim) s)]
    (if (find-at m s (min (.length s) (inc (eol s i)))) (.end m) (.length s))))

(defn- lex-elixir
  "# comments; strings, charlists, heredocs and sigils (with #{} interpolation
  where Elixir has it); and character literals such as ?# and ?\". A ? after
  a name character ends the name (empty?) instead."
  [^String s]
  (let [m (re-matcher elixir-token s) n (.length s)]
    (loop [i 0 spans []]
      (if-let [j (find-at m s i)]
        (let [tok (.group m) e (.end m)]
          (cond
            (= tok "#")
            (let [end (eol s j)] (recur end (conj spans [j end :comment])))

            (= tok "?")
            (if (some->> (char-at s (dec j)) str (re-matches #"[\w!?]"))
              (recur e spans)
              (let [end (min n (+ j (if (= \\ (char-at s (inc j))) 3 2)))]
                (recur end (conj spans [j end :string]))))

            (or (str/ends-with? tok "\"\"\"") (str/ends-with? tok "'''"))
            (let [end (heredoc-end s e (subs tok (- (count tok) 3)))]
              (recur end (conj spans [j end :string])))

            (#{"\"" "'"} tok)
            (let [end (literal-end s e tok {:interp? true})] (recur end (conj spans [j end :string])))

            :else                       ; a sigil: ~r/.../, ~w(...), ~S|...|
            (let [open (subs tok (dec (count tok)))
                  end  (literal-end s e (sigil-close open open)
                                    {:interp? (Character/isLowerCase (.charAt s (int (inc j))))})]
              (recur end (conj spans [j end :string])))))
        spans))))

;; --- the two masked copies

(defn- splice!
  "Replace sb[from, to) with f applied to it; f must keep the length."
  [^StringBuilder sb from to f]
  (.replace sb (int from) (int to) ^String (f (.substring sb (int from) (int to)))))

(defn- replace-keeping-length
  "Replace each match of re with mark, then each character outside the BMP
  with two marks. Offsets are UTF-16 units, and a regex matches such a
  character (two units) as one, so re must exclude them."
  [s re mark]
  (-> s (str/replace re mark) (str/replace #"[\x{10000}-\x{10FFFF}]" (str mark mark))))

(defn- blank-out [s] (replace-keeping-length s #"[^\n\x{10000}-\x{10FFFF}]" " "))

(defn- mark-non-blank [mark]
  (fn [s] (replace-keeping-length s #"[^\p{javaWhitespace}\x{10000}-\x{10FFFF}]" mark)))

(defn- masked-text
  "s with comments blanked and doc comments marked as documentation."
  [^String s spans]
  (let [sb (StringBuilder. s)]
    (doseq [[a b kind] spans]
      (case kind
        :comment (splice! sb a b blank-out)
        :doc     (splice! sb a b (mark-non-blank doc-mark))
        :string  nil))
    (str sb)))

(defn- bare-text
  "s with comments and doc comments blanked and string contents replaced by
  str-mark, for finding keywords and brackets in code."
  [^String s spans]
  (let [sb (StringBuilder. s)]
    (doseq [[a b kind] spans]
      (splice! sb a b (if (= kind :string)
                        #(replace-keeping-length % #"[^\n\x{10000}-\x{10FFFF}]" str-mark)
                        blank-out)))
    (str sb)))

(defn- mark-ranges
  "masked with doc-ranges marked as documentation, then test-ranges marked as
  tests. Test marking leaves comments and documentation as they are."
  [^String masked doc-ranges test-ranges]
  (let [sb (StringBuilder. masked)]
    (doseq [[a b] doc-ranges] (splice! sb a b (mark-non-blank doc-mark)))
    (doseq [[a b] test-ranges]
      (splice! sb a b #(replace-keeping-length % #"[^\p{javaWhitespace}\uE001\x{10000}-\x{10FFFF}]" test-mark)))
    (str sb)))

(defn- line-kinds
  "One of :code, :test, :doc or nil (blank or comment) per line of a masked text."
  [^String masked]
  (mapv (fn [line]
          (cond (re-find #"[^\p{javaWhitespace}\uE001\uE002]" line) :code
                (str/includes? line test-mark)                     :test
                (str/includes? line doc-mark)                      :doc))
        (str/split-lines masked)))

(defn- tally [kinds]
  (let [f (frequencies kinds)]
    {:file/code-lines (f :code 0) :file/doc-lines (f :doc 0) :file/test-lines (f :test 0)}))

;; --- structure passes: [start end] ranges of documentation and inline tests

(defn- zig-test-ranges
  "Test declarations: test \"name\" { }, test name { } and test { }, braces
  matched in code only. test is a keyword in Zig, so it can't be a name."
  [^String bare]
  (let [m  (re-matcher #"(?<![\w.@])test(?!\w)" bare)
        hm (re-matcher #"\s*(?:@?\uE003+|[A-Za-z_]\w*)?\s*\{" bare)
        bm (re-matcher #"[{}]" bare)
        n  (.length bare)]
    (loop [i 0 acc []]
      (if-let [start (find-at m bare i)]
        (let [e (.end m)]
          (.region hm (int e) (int n))
          (if (.lookingAt hm)
            (let [close (loop [k (.end hm) depth 1]
                          (if-let [b (find-at bm bare k)]
                            (cond (= \{ (.charAt bare (int b))) (recur (inc b) (inc depth))
                                  (= 1 depth)                   (inc b)
                                  :else                         (recur (inc b) (dec depth)))
                            n))]
              (recur close (conj acc [start close])))
            (recur e acc)))
        acc))))

(defn- ocaml-test-ranges
  "ppx inline tests (let%test, let%test_unit, let%expect_test,
  let%test_module), from the keyword up to the next line whose code starts at
  or left of the keyword's column: the next item at the same level, or the
  end of the enclosing struct. Lines inside a string or comment don't count,
  so an expect block's output at column 0 stays in the test. A line holding
  just the ;; that terminates the test is part of it."
  [^String bare]
  (let [m (re-matcher #"(?<![\w'.])let%(?:test|test_unit|expect_test|test_module)(?![\w'])" bare)
        n (.length bare)]
    (loop [i 0 acc []]
      (if-let [start (find-at m bare i)]
        (let [col  (- start (inc (.lastIndexOf bare "\n" (int (dec start)))))
              em   (re-matcher (re-pattern (str "(?md)^[ \\t]{0," col "}[^\\s\\uE003]")) bare)
              nxt  (find-at em bare (min n (inc (eol bare start))))
              end  (cond
                     (nil? nxt) n
                     (re-matches #"[ \t]*;;\s*" (subs bare nxt (eol bare nxt))) (min n (inc (eol bare nxt)))
                     :else nxt)]
          (recur end (conj acc [start end])))
        acc))))

(def ^:private elixir-block-token #"(?<![\w.:@])(?:do|fn|end)(?![\w?!:])")

(defn- elixir-block-end
  "Index just past the `end` that closes a do or fn block opened before i."
  [^String bare i]
  (let [m (re-matcher elixir-block-token bare)]
    (loop [i i depth 1]
      (if (find-at m bare i)
        (let [e (.end m)]
          (cond (not= "end" (.group m)) (recur e (inc depth))
                (= 1 depth)             e
                :else                   (recur e (dec depth))))
        (.length bare)))))

(defn- elixir-doc-ranges
  "@moduledoc, @doc, @typedoc and @shortdoc with a string, heredoc or sigil
  value, from the @ to the end of the literal. @doc false is code."
  [^String bare spans]
  (let [ends (into {} (keep (fn [[a b kind]] (when (= kind :string) [a b]))) spans)
        m    (re-matcher #"@(?:moduledoc|doc|typedoc|shortdoc)(?![\w?!])[ \t]*" bare)]
    (loop [acc []]
      (if (.find m)
        (recur (if-let [e (ends (.end m))] (conj acc [(.start m) e]) acc))
        acc))))

(defn- elixir-test-ranges
  "ExUnit tests inside a source file that has `use ExUnit.Case`: whole
  modules named *Test that use it; test, describe and setup blocks elsewhere;
  and use ExUnit.Case, doctest and ExUnit.* lines."
  [^String bare]
  (when (re-find #"(?<![\w.])use\s+ExUnit\.Case(?![\w.])" bare)
    (let [mm (re-matcher #"(?<![\w.:@])defmodule\s+([\w.]+)\s+do(?![\w?!:])" bare)
          modules (loop [acc []]
                    (if (.find mm)
                      (let [start (.start mm) name (.group mm 1) end (elixir-block-end bare (.end mm))]
                        (recur (cond-> acc
                                 (and (str/ends-with? name "Test")
                                      (re-find #"use\s+ExUnit\.Case" (subs bare start end)))
                                 (conj [start end]))))
                      acc))
          ;; test "name" do ... end; a bodyless test "name" covers its line.
          ;; test = ..., test(...) and test.x are a variable or a call.
          bm (re-matcher #"(?m)^[ \t]*((?:test|describe|setup|setup_all)(?![\w?!:])(?![ \t]*(?:=[^=]|\(|\.)))" bare)
          tm (re-matcher elixir-block-token bare)
          blocks (loop [acc []]
                   (if (.find bm)
                     (let [start (.start bm 1) line-end (eol bare start)
                           do-at (when (and (find-at tm bare (.end bm 1)) (= "do" (.group tm))) (.start tm))]
                       (recur (conj acc [start (if (and do-at (< do-at line-end))
                                                 (elixir-block-end bare (+ do-at 2))
                                                 line-end)])))
                     acc))
          lm (re-matcher #"(?m)^[ \t]*((?:use[ \t]+ExUnit\.Case|doctest[ \t]|ExUnit\.\w+).*)$" bare)
          lines (loop [acc []] (if (.find lm) (recur (conj acc [(.start lm 1) (.end lm 1)])) acc))]
      (concat modules blocks lines))))

(def ^:private docstring-heads #{"ns" "def" "defn" "defn-" "defmacro" "defmulti" "defprotocol"})

(def ^:private test-heads #{"deftest" "defspec" "use-fixtures" "run-tests" "run-all-tests"})

(def ^:private edamame-opts
  {:all true :auto-resolve (fn [_] 'user) :readers (fn [_] identity)
   :read-cond :allow :features #{:clj :cljs}
   ;; A string can't carry metadata, so it's wrapped with its location. With
   ;; :postprocess edamame stops adding location metadata, so lists get it here.
   :postprocess (fn [{:keys [obj loc]}]
                  (cond (string? obj) {::string obj ::loc loc}
                        (seq? obj)    (with-meta obj loc)
                        :else         obj))})

(defn- string-form? [x] (and (map? x) (contains? x ::string)))

(defn- head-name
  "The unqualified name of a list's head symbol, e.g. \"deftest\" for t/deftest."
  [form]
  (let [h (first form)] (when (symbol? h) (name h))))

(defn- docstring-forms
  "The (wrapped) docstrings of a top-level form, given its head name:
  (def x \"doc\" v), (defn f \"doc\" ...), (ns a \"doc\"), and a
  defprotocol's own and per-method docstrings."
  [head form]
  (let [[_ _ doc & more] form]
    (concat
     (when (and (docstring-heads head) (string-form? doc)
                (case head
                  ("ns" "defprotocol") true
                  "def"                (= 1 (count more))
                  (boolean (seq more))))
       [doc])
     (when (= "defprotocol" head)
       (for [sig (drop 2 form)
             :when (and (seq? sig) (> (count sig) 1) (string-form? (last sig)))]
         (last sig))))))

(defn- row-starts
  "Offset where each row starts, splitting rows as edamame does (\\r\\n, \\n or \\r)."
  [^String s]
  (let [m (re-matcher #"\r\n|\n|\r" s)]
    (loop [acc [0]] (if (.find m) (recur (conj acc (.end m))) acc))))

(defn- clojure-structure
  "{:comments :docs :tests}: [start end] ranges of top-level (comment ...)
  forms, docstrings, and top-level deftest-like forms. edamame reads without
  evaluating. nil if the text doesn't parse, including when nesting is deep
  enough to overflow the stack (an Error, so Throwable is caught)."
  [^String s]
  (try
    (let [rows   (row-starts s)
          offset (fn [row col] (+ (long (nth rows (dec row))) (dec col)))
          span   (fn [{:keys [row col end-row end-col]}] [(offset row col) (offset end-row end-col)])
          forms  (vec (for [form (e/parse-string-all s edamame-opts)
                            :when (and (seq? form) (:row (meta form)))
                            :let [head (head-name form)]
                            :when head]
                        {:head head :span (span (meta form))
                         :docs (mapv (comp span ::loc) (docstring-forms head form))}))]
      {:comments (into [] (comp (filter #(= "comment" (:head %))) (map :span)) forms)
       :docs     (into [] (mapcat :docs) forms)
       :tests    (into [] (comp (filter #(test-heads (:head %))) (map :span)) forms)})
    (catch Throwable _ nil)))

(defn- newline-offsets [^String s]
  (loop [i 0 acc []]
    (let [j (.indexOf s "\n" (int i))]
      (if (neg? j) acc (recur (inc j) (conj acc (long j)))))))

(defn- line-of
  "1-based line (lines split on \\n) holding offset off."
  [newlines off]
  (let [r (java.util.Collections/binarySearch newlines (long off))]
    (inc (if (neg? r) (- (inc r)) r))))

(defn count-lines
  "Line counts for one source file:
    :file/lines       all lines
    :file/code-lines  lines with code, not counting blank lines, comments,
                      documentation or inline tests
    :file/doc-lines   lines with only documentation: doc comments, Clojure
                      docstrings, Elixir @moduledoc/@doc/@typedoc
    :file/test-lines  lines with only inline tests: Zig test blocks, OCaml
                      let%test and friends, top-level Clojure deftest and
                      friends, ExUnit tests in a non-test file
  Clojure adds :file/comment-rows, the [start end] lines (1-based, inclusive)
  of top-level (comment ...) forms, and :file/code-lines-no-comment-forms,
  the code lines outside them. When a Clojure file doesn't parse, its
  docstrings and tests can't be found: those four keys are nil and
  :file/code-lines counts docstrings and tests as code."
  [lang text]
  (let [spans  (case lang
                 :clojure (lex-clojure text)
                 :elixir  (lex-elixir text)
                 :zig     (lex-zig text)
                 :ocaml   (lex-ocaml text))
        masked (masked-text text spans)
        base   {:file/lines (count (str/split-lines text))}]
    (if (= lang :clojure)
      (let [st    (clojure-structure text)
            kinds (line-kinds (mark-ranges masked (:docs st) (:tests st)))]
        (if-not st
          (merge base (tally kinds) {:file/doc-lines nil :file/test-lines nil
                                     :file/comment-rows nil :file/code-lines-no-comment-forms nil})
          (let [nls  (newline-offsets text)
                rows (mapv (fn [[a b]] [(line-of nls a) (line-of nls (dec b))]) (:comments st))
                in-comment-form? (fn [line] (some (fn [[a b]] (<= a line b)) rows))]
            (merge base (tally kinds)
                   {:file/comment-rows rows
                    :file/code-lines-no-comment-forms
                    (count (keep-indexed (fn [i k] (when (and (= k :code) (not (in-comment-form? (inc i)))) k))
                                         kinds))}))))
      (let [bare  (bare-text text spans)
            docs  (when (= lang :elixir) (elixir-doc-ranges bare spans))
            tests (case lang
                    :elixir (elixir-test-ranges bare)
                    :zig    (zig-test-ranges bare)
                    :ocaml  (ocaml-test-ranges bare))]
        (merge base (tally (line-kinds (mark-ranges masked docs tests))))))))

(defn strip-ocaml-comments
  "s without its comments and doc comments (which nest in OCaml), keeping
  the newlines inside them. String and character literals are left alone,
  so \"(*\" doesn't start a comment."
  [^String s]
  (let [sb (StringBuilder. s)]
    (doseq [[a b kind] (rseq (lex-ocaml s)) :when (not= kind :string)]
      (.replace sb (int a) (int b) ^String (str/replace (subs s a b) #"[^\n]" "")))
    (str sb)))

(defn livebook-elixir
  "The Elixir code of a Livebook (.livemd) notebook: every line outside an
  ```elixir cell is blanked, so line numbers are kept. A cell after a
  force_markdown annotation is displayed rather than run, so it's blanked too."
  [text]
  (loop [[l & more :as ls] (str/split text #"\n" -1), in-cell? false, prev "", out []]
    (cond
      (empty? ls)
      (str/join "\n" out)

      in-cell?
      (if (re-matches #"\s*```\s*" l)
        (recur more false l (conj out ""))
        (recur more true l (conj out l)))

      (and (re-matches #"\s*```elixir\s*" l) (not (str/includes? prev "force_markdown")))
      (recur more true l (conj out ""))

      :else
      (recur more false (if (str/blank? l) prev l) (conj out "")))))

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

(defn- small-file?
  "A regular file (not a symlink) no bigger than max-file-bytes."
  [p]
  (and (fs/regular-file? p {:nofollow-links true}) (<= (fs/size p) max-file-bytes)))

(def ^:private java-version-files [".java-version" ".tool-versions" ".sdkmanrc"])

(defn java-version
  "First Java version declared by a version-manager file or a setup-java
  step, as [where version]. Files over max-file-bytes are not read."
  [repo-dir]
  (or (some (fn [f]
              (let [p (fs/path repo-dir f)]
                (when (small-file? p)
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
                  (when (small-file? p)
                    (some->> (re-find #"java-version:\s*['\"]?(\d+(?:\.\d+)*)" (slurp (str p)))
                             second
                             (vector ".github/workflows"))))
                (fs/list-dir wf))))))

;; ---------------------------------------------------------------------------
;; Templates: generated or copied-in day files that were never filled in
;;
;; Only whole files are caught. Boilerplate a template leaves inside a solved
;; file (e.g. a footer of stdlib aliases in Zig day files) is counted as code:
;; a rule for lines shared verbatim across a repo's day files can't tell it
;; from helper code a solver copied into each day, which is solution code.

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
                                ;; a set, so part files of one puzzle count it once
                                (if (and fingerprint (seq keys)) (update m fingerprint (fnil into #{}) keys) m))
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

(def ^:private git-env
  ;; As in research.clone: never prompt, never run an askpass helper. And
  ;; take no optional locks, so reading the index never rewrites it.
  {"GIT_TERMINAL_PROMPT" "0" "GIT_ASKPASS" "/usr/bin/false" "SSH_ASKPASS" "/usr/bin/false"
   "GIT_CONFIG_NOSYSTEM" "1" "GIT_OPTIONAL_LOCKS" "0"})

(def ^:private git-safety
  ;; research.clone's flags (no credential helpers, no hooks), plus no
  ;; fsmonitor, which is configured as a command for git to run.
  ["-c" "credential.helper=" "-c" "core.askPass=/usr/bin/false" "-c" "core.hooksPath=/dev/null"
   "-c" "core.symlinks=false" "-c" "init.templateDir=" "-c" "protocol.file.allow=never"
   "-c" "core.fsmonitor=false"])

(defn symlink-paths
  "Paths that git records as symlinks (mode 120000) in a clone's index. The
  clone checks them out as plain files holding the link target
  (core.symlinks=false), so on disk they look like one-line source files.
  Empty when repo-dir has no .git of its own; throws if git fails."
  [repo-dir]
  (if-not (fs/exists? (fs/path repo-dir ".git"))
    #{}
    (let [{:keys [exit out err]} (apply p/shell {:dir (str repo-dir) :out :string :err :string
                                                 :continue true :extra-env git-env}
                                        "git" (concat git-safety ["ls-files" "-s" "-z"]))]
      (when-not (zero? exit)
        (throw (ex-info "git ls-files failed" {:dir (str repo-dir) :err err})))
      (into #{}
            (keep #(when (str/starts-with? % "120000 ") (second (str/split % #"\t" 2))))
            (str/split out #"\u0000")))))

(defn- source-entry
  "Summary of one source file. Never throws: an unexpected error in one file
  becomes :file/status :failed for that file instead of failing the repo."
  [{:keys [repo repo-name repo-dir symlinks]} rel ext lang]
  (try
    (let [p (fs/path repo-dir rel) size (fs/size p)]
      (cond
        (symlinks rel)
        {:file/path rel :file/lang lang :file/status :symlink :file/bytes size}

        (> size max-file-bytes)
        {:file/path rel :file/lang lang :file/status :too-large :file/bytes size}

        :else
        (let [text    (slurp (str p))
              ns-name (module-name lang text)]
          (merge {:file/path rel :file/lang lang :file/status :ok :file/bytes size
                  :file/runtime (runtime ext text) :file/module ns-name}
                 (if (= ext "livemd")
                   (assoc (count-lines lang (livebook-elixir text)) :file/lines (count (str/split-lines text)))
                   (count-lines lang text))
                 {:file/fingerprint (fingerprint text)}
                 (puzzle/map-file {:path rel :ns-name ns-name :repo-name repo-name
                                   :created-at (:repo/created-at repo)})))))
    (catch Throwable t
      (let [msg (str (.getName (class t)) ": " (.getMessage t))]
        {:file/path rel :file/lang lang :file/status :failed
         :file/error (subs msg 0 (min 500 (count msg)))}))))

(defn summarize
  "repo (from data/repos.edn) + its clone dir -> EDN summary."
  [repo repo-dir]
  (let [rels    (repo-files repo-dir)
        ctx     {:repo repo :repo-dir repo-dir
                 :repo-name (names/repo-name (:repo/full-name repo))
                 :symlinks (symlink-paths repo-dir)}
        sources (vec (for [rel rels
                           :let [ext (fs/extension rel) lang (ext->lang ext)]
                           :when lang]
                       (source-entry ctx rel ext lang)))]
    {:repo/id             (:repo/id repo)
     :repo/lang           (:repo/lang repo)
     :repo/build-files    (into (sorted-set) (keep (comp build-files fs/file-name)) rels)
     :repo/ai-files       (present repo-dir ai-files)
     :repo/editor-signals (present repo-dir editor-signals)
     :repo/java-version   (java-version repo-dir)
     :repo/declarations   (deps/declarations repo-dir rels)
     :repo/files          (mark-templates sources)}))
