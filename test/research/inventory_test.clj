(ns research.inventory-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [research.inventory :as inv]))

(defn- counts
  "The line-count keys of count-lines, for compact assertions."
  [lang text]
  (select-keys (inv/count-lines lang text) [:file/code-lines :file/doc-lines :file/test-lines]))

(deftest clojure-lines
  (let [text "(ns aoc.y2021.day07)\n\n;; a comment\n(defn f [x]\n  (inc x))\n\n(comment\n  (f 1)\n  )\n"]
    (is (= {:file/lines 9 :file/code-lines 6 :file/code-lines-no-comment-forms 3
            :file/doc-lines 0 :file/test-lines 0 :file/comment-rows [[7 9]]}
           (inv/count-lines :clojure text)))
    (is (nil? (:file/code-lines-no-comment-forms (inv/count-lines :clojure "(defn broken [")))
        "unparseable -> nil, not a wrong number")))

(deftest other-languages
  (is (= 2 (:file/code-lines (inv/count-lines :elixir "# hi\ndefmodule A do\n\n  # x\nend\n"))))
  (is (= 2 (:file/code-lines (inv/count-lines :zig "// a\n/// doc\nconst a = 1;\n\npub fn main() void {}\n"))))
  (is (= "let x = 1 \n\nlet y = 2" (inv/strip-ocaml-comments "let x = 1 (* a (* nested *) b *)\n\nlet y = 2")))
  (is (= 2 (:file/code-lines (inv/count-lines :ocaml "(* header\n   spans lines *)\nlet x = 1\n\nlet y = 2\n")))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [0]: inline tests are not solution code, in any language

(deftest zig-inline-tests
  (let [text (str "const std = @import(\"std\");\n"
                  "/// Doc for solve\n"
                  "pub fn solve(s: []const u8) usize {\n"
                  "    return s.len; // \"}\" in a comment\n"
                  "}\n"
                  "\n"
                  "test \"solve\" {\n"
                  "    const t = \"}{\";\n"
                  "    try std.testing.expect(solve(t) == 2);\n"
                  "}\n"
                  "test {\n"
                  "    _ = '{';\n"
                  "}\n"
                  "test solve {\n"
                  "    _ = 1;\n"
                  "}\n"
                  "pub fn main() void {}\n")]
    (is (= {:file/code-lines 5 :file/doc-lines 1 :file/test-lines 10} (counts :zig text)))))

(deftest ocaml-inline-tests
  (let [text (str "let solve x = x + 1\n"
                  "\n"
                  "let%test \"solve\" = solve 1 = 2\n"
                  "\n"
                  "let%expect_test _ =\n"
                  "  print_int (solve 1);\n"
                  "  [%expect {|\n"
                  "2\n"
                  "|}]\n"
                  "\n"
                  "(** Part two *)\n"
                  "let part2 x =\n"
                  "  x * 2\n"
                  "\n"
                  "let%test_unit \"p2\" =\n"
                  "  assert (part2 1 = 2)\n")]
    (is (= {:file/code-lines 3 :file/doc-lines 1 :file/test-lines 8} (counts :ocaml text))))
  (testing "a ;; that ends a test belongs to it; {%expect|...|} is a string"
    (is (= {:file/code-lines 1 :file/doc-lines 0 :file/test-lines 4}
           (counts :ocaml (str "let%expect_test \"a\" =\n"
                               "  f ~expect:(fun () -> {%expect| \" (* |})\n"
                               "  |> ignore\n"
                               ";;\n"
                               "\n"
                               "let x = 1\n")))))
  (testing "inside a struct, the test ends at the next item or at end"
    (is (= {:file/code-lines 5 :file/doc-lines 0 :file/test-lines 1}
           (counts :ocaml "module M = struct\n  let a = 1\n  let%test _ = a = 1\n  let b = 2\nend\nlet c = 3\n")))))

(deftest clojure-inline-tests
  (let [text (str "(ns aoc.day01\n"
                  "  (:require [clojure.test :refer [deftest is]]))\n"
                  "\n"
                  "(defn solve [xs] (reduce + xs))\n"
                  "\n"
                  "(deftest solve-test\n"
                  "  (is (= 3 (solve [1 2]))))\n")]
    (is (= {:file/code-lines 3 :file/doc-lines 0 :file/test-lines 2} (counts :clojure text)))))

(deftest elixir-inline-tests
  (testing "a separate *Test module in a solution script"
    (is (= {:file/code-lines 3 :file/doc-lines 0 :file/test-lines 8}
           (counts :elixir (str "defmodule Day1 do\n"
                                "  def solve(x), do: x + 1\n"
                                "end\n"
                                "\n"
                                "ExUnit.start()\n"
                                "\n"
                                "defmodule Day1Test do\n"
                                "  use ExUnit.Case\n"
                                "  doctest Day1\n"
                                "\n"
                                "  test \"solve\" do\n"
                                "    assert Day1.solve(1) == 2\n"
                                "  end\n"
                                "end\n")))))
  (testing "tests inside the solution module: only the test blocks go"
    (is (= {:file/code-lines 5 :file/doc-lines 0 :file/test-lines 4}
           (counts :elixir (str "defmodule Day2 do\n"
                                "  use ExUnit.Case\n"
                                "  def solve(x) do\n"
                                "    x * 2\n"
                                "  end\n"
                                "\n"
                                "  test \"solve\" do\n"
                                "    assert solve(2) == 4\n"
                                "  end\n"
                                "end\n")))))
  (testing "no ExUnit, no tests: a function called test is code"
    (is (= {:file/code-lines 3 :file/doc-lines 0 :file/test-lines 0}
           (counts :elixir "defmodule A do\n  def test(x), do: x\nend\n")))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [1]: documentation is not code, in any language

(deftest elixir-docs
  (testing "@moduledoc heredoc, @doc string; @doc false is code"
    (is (= {:file/code-lines 5 :file/doc-lines 4 :file/test-lines 0}
           (counts :elixir (str "defmodule A do\n"
                                "  @moduledoc \"\"\"\n"
                                "  Solves day 1.\n"
                                "  \"\"\"\n"
                                "  @doc \"Part one\"\n"
                                "  def f, do: 1\n"
                                "  @doc false\n"
                                "  def g, do: 2\n"
                                "end\n")))))
  (testing "@typedoc, sigil heredocs and doctests inside @doc"
    (is (= {:file/code-lines 4 :file/doc-lines 6 :file/test-lines 0}
           (counts :elixir (str "defmodule A do\n"
                                "  @typedoc \"A grid\"\n"
                                "  @type grid :: map\n"
                                "  @doc ~S\"\"\"\n"
                                "  Runs it.\n"
                                "\n"
                                "      iex> A.f()\n"
                                "      1\n"
                                "  \"\"\"\n"
                                "  def f, do: 1\n"
                                "end\n"))))))

(deftest clojure-docstrings
  (let [text (str "(ns aoc.day01\n"
                  "  \"Day 1.\"\n"
                  "  (:require [clojure.string :as str]))\n"
                  "\n"
                  "(defn solve\n"
                  "  \"Sum the\n"
                  "  numbers.\"\n"
                  "  [xs]\n"
                  "  (reduce + xs))\n"
                  "\n"
                  "(def answer \"not a docstring\")\n"
                  "(def ^:private limit \"A docstring\" 10)\n"
                  "(defn- one-line \"doc on the code line\" [] 1)\n"
                  "\n"
                  "(comment\n"
                  "  (solve [1]))\n")]
    (is (= {:file/code-lines 10 :file/doc-lines 3 :file/test-lines 0} (counts :clojure text)))
    (is (= [[15 16]] (:file/comment-rows (inv/count-lines :clojure text))))
    (is (= 8 (:file/code-lines-no-comment-forms (inv/count-lines :clojure text))))))

(deftest zig-and-ocaml-docs
  (is (= {:file/code-lines 1 :file/doc-lines 2 :file/test-lines 0}
         (counts :zig "//! Day 1\n/// doc\n//// not doc\nconst a = 1;\n")))
  (is (= {:file/code-lines 1 :file/doc-lines 2 :file/test-lines 0}
         (counts :ocaml "(** Day 1\n    doc *)\n(**)\n(*** banner ***)\nlet x = 1\n"))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [10]: '#' inside an Elixir string is not a comment

(deftest elixir-hash-in-strings
  (is (= 9 (:file/code-lines
            (inv/count-lines :elixir (str "defmodule A do\n"
                                          "  @example \"\"\"\n"
                                          "  #.##\n"
                                          "  ..#.\n"
                                          "  #...\n"
                                          "  \"\"\"\n"
                                          "  def f, do: \"#{1}\n# not a comment\"\n"
                                          "end\n")))))
  (is (= 2 (:file/code-lines (inv/count-lines :elixir "x = ?#\ny = ~r/#/ # trailing\n# comment\n")))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [11]: OCaml comment markers inside strings

(deftest ocaml-strings-are-not-comments
  (is (= 4 (:file/code-lines (inv/count-lines :ocaml "let s = \"(*\" in\nlet x = 1 in\nlet y = 2 (* c *) in\nx + y\n"))))
  (is (= 4 (:file/code-lines (inv/count-lines :ocaml "let s = {|(*|} in\nlet x = 1 in\nlet y = 2 (* c *) in\nx + y\n"))))
  (is (= 4 (:file/code-lines (inv/count-lines :ocaml "let s = {id|(*|}|id} in\nlet x = 1 in\nlet y = 2 (* c *) in\nx + y\n"))))
  (is (= 4 (:file/code-lines (inv/count-lines :ocaml "let s = {%sql|(*|} in\nlet x = {%ext id|\"|id} in\nlet y = 2 (* {%e|*)|} *) in\nx + y\n"))))
  (is (= 3 (:file/code-lines (inv/count-lines :ocaml "let q = '\"' in\nlet s = \"(*\" ^ \"a\" in\n(* \"*)\" still a comment *)\nq\n"))))
  (is (= 2 (:file/code-lines (inv/count-lines :ocaml "let f' x = x in\nlet g (x : 'a) = '\\'' in\n(* it's *)\n"))))
  (is (= "let s = \"(*\" \nlet y = 2"
         (inv/strip-ocaml-comments "let s = \"(*\" (* c *)\nlet y = 2"))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [2]: one puzzle shared by part files is not a template

(deftest templates
  (testing "same content (digits normalised) for 2+ days in one repo = template"
    (let [files [{:file/path "src/day01.zig" :file/fingerprint "A" :puzzle/keys #{[2023 1]}}
                 {:file/path "src/day02.zig" :file/fingerprint "A" :puzzle/keys #{[2023 2]}}
                 {:file/path "src/day03.zig" :file/fingerprint "B" :puzzle/keys #{[2023 3]}}]]
      (is (= [true true false] (map :file/template? (inv/mark-templates files))))))
  (testing "part 1 and part 2 of one puzzle that differ only by a number are not templates"
    (let [files [{:file/path "src/day14/day14_a.clj" :file/fingerprint "A" :puzzle/keys #{[2021 14]}}
                 {:file/path "src/day14/day14_b.clj" :file/fingerprint "A" :puzzle/keys #{[2021 14]}}]]
      (is (= [false false] (map :file/template? (inv/mark-templates files))))))
  (is (= (inv/fingerprint "pub fn main() void { day(1); }") (inv/fingerprint "pub fn main() void {\n  day(23); }"))))

;; ---------------------------------------------------------------------------
;; mapping-and-counting [13]: Livebook notebooks count their elixir cells

(def livebook
  (str "# Day 1\n"
       "\n"
       "```elixir\n"
       "Mix.install([])\n"
       "```\n"
       "\n"
       "Some prose with a\n"
       "# heading-like line and `code`.\n"
       "\n"
       "<!-- livebook:{\"force_markdown\":true} -->\n"
       "\n"
       "```elixir\n"
       "IO.puts(\"shown, not run\")\n"
       "```\n"
       "\n"
       "```elixir\n"
       "defmodule Day1 do\n"
       "  # comment\n"
       "  def run, do: 1\n"
       "end\n"
       "```\n"))

(deftest livebook-cells
  (is (= :elixir (inv/ext->lang "livemd")))
  (is (= 4 (:file/code-lines (inv/count-lines :elixir (inv/livebook-elixir livebook)))))
  (is (= (count (str/split livebook #"\n" -1)) (count (str/split (inv/livebook-elixir livebook) #"\n" -1)))
      "line numbers are kept"))

(deftest characters-outside-the-bmp
  ;; An emoji is two UTF-16 units; masking must keep offsets aligned.
  (is (= {:file/code-lines 2 :file/doc-lines 1 :file/test-lines 0}
         (counts :clojure ";; 😀 star\n(defn f\n  \"😀 doc\"\n  [] \"😀\")\n;; end 😀")))
  (is (= {:file/code-lines 1 :file/doc-lines 1 :file/test-lines 1}
         (counts :zig "// 😀\n/// 😀\nconst a = \"😀\";\ntest \"😀\" {}\n// 😀")))
  (is (= {:file/code-lines 1 :file/doc-lines 1 :file/test-lines 0}
         (counts :ocaml "(* 😀 *)\n(** 😀 *)\nlet a = \"😀\" (* 😀 *)\n(* 😀 *)")))
  (is (= {:file/code-lines 1 :file/doc-lines 1 :file/test-lines 0}
         (counts :elixir "# 😀\n@doc \"😀\"\ndef a, do: \"😀\" # 😀\n# 😀"))))

(deftest module-names-and-runtime
  (is (= "aoc.y2021.day07" (inv/module-name :clojure "(ns aoc.y2021.day07\n  (:require [clojure.string :as str]))")))
  (is (= "aoc.core" (inv/module-name :clojure "(ns ^:no-doc aoc.core)")))
  (is (= "AoC2021.Day07" (inv/module-name :elixir "defmodule AoC2021.Day07 do\nend")))
  (is (= :bb (inv/runtime "clj" "#!/usr/bin/env bb\n(println 1)")))
  (is (= :jvm (inv/runtime "clj" "(ns a)"))))

;; ---------------------------------------------------------------------------
;; security [2]: deep nesting must not fail the file, let alone the repo

(deftest deep-nesting-does-not-throw
  (let [deep (str (apply str (repeat 100000 "[")) (apply str (repeat 100000 "]")))
        ;; The extract stage runs on pool threads, whose stacks are small.
        res  @(future (try (inv/count-lines :clojure deep) (catch Throwable t t)))]
    (is (map? res))
    (is (= 1 (:file/code-lines res)))
    (is (nil? (:file/comment-rows res)))
    (is (nil? (:file/code-lines-no-comment-forms res)))))

;; ---------------------------------------------------------------------------
;; Whole-repo summaries: symlinks (security [8]), oversize version files
;; (security [7]), Livebooks, and one bad file not failing the repo

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string
                  :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t"}}
         "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args))

(defn- temp-repo
  "A git repo in a temp dir with src/util.zig, a symlink src/day02.zig -> util.zig
  that is then replaced in the working tree by a plain file holding the
  target (what a core.symlinks=false checkout produces), and day01.livemd."
  []
  (let [dir (fs/create-temp-dir)]
    (fs/create-dirs (fs/path dir "src"))
    (spit (str (fs/path dir "src" "util.zig")) "pub fn f() void {}\n")
    (spit (str (fs/path dir "src" "day01.zig")) "const std = @import(\"std\");\npub fn main() void {}\n")
    (spit (str (fs/path dir "day01.livemd")) livebook)
    (fs/create-sym-link (fs/path dir "src" "day02.zig") (fs/path "util.zig"))
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "init")
    (fs/delete (fs/path dir "src" "day02.zig"))
    (spit (str (fs/path dir "src" "day02.zig")) "util.zig")
    dir))

(def repo {:repo/id 1 :repo/lang :zig :repo/full-name "someone/advent-of-code-2023"
           :repo/created-at "2023-12-01T00:00:00Z"})

(deftest symlinks-are-not-source-files
  (let [dir (temp-repo)]
    (try
      (is (= #{"src/day02.zig"} (inv/symlink-paths dir)))
      (is (= #{} (inv/symlink-paths (fs/create-temp-dir))) "not a git repo -> none")
      (let [by-path (into {} (map (juxt :file/path identity)) (:repo/files (inv/summarize repo dir)))
            link    (by-path "src/day02.zig")]
        (is (= :symlink (:file/status link)))
        (is (not-any? link [:file/code-lines :file/lines :file/role :puzzle/keys]))
        (is (= :ok (:file/status (by-path "src/day01.zig"))))
        (is (= 2 (:file/code-lines (by-path "src/day01.zig"))))
        (testing "Livebook files are Elixir, counted from their code cells"
          (is (= :elixir (:file/lang (by-path "day01.livemd"))))
          (is (= 4 (:file/code-lines (by-path "day01.livemd"))))
          (is (= (count (str/split-lines livebook)) (:file/lines (by-path "day01.livemd"))))))
      (finally (fs/delete-tree dir)))))

(deftest summaries-round-trip-as-edn
  (let [dir (temp-repo)]
    (try
      (spit (str (fs/path dir "src" "day03.clj")) "(ns day03 \"Doc.\")\n(comment (+ 1 2))\n")
      (let [s (inv/summarize repo dir)]
        (is (= s (edn/read-string (pr-str s))))
        (is (= [[2 2]] (:file/comment-rows (first (filter #(= "src/day03.clj" (:file/path %)) (:repo/files s)))))))
      (finally (fs/delete-tree dir)))))

(deftest one-bad-file-does-not-fail-the-repo
  (let [dir (temp-repo)
        real inv/count-lines]
    (try
      (with-redefs [inv/count-lines (fn [lang text]
                                      (if (str/includes? text "@import") (throw (Error. "not an Exception")) (real lang text)))]
        (let [by-path (into {} (map (juxt :file/path identity)) (:repo/files (inv/summarize repo dir)))]
          (is (= :failed (:file/status (by-path "src/day01.zig"))))
          (is (= :ok (:file/status (by-path "src/util.zig"))))))
      (finally (fs/delete-tree dir)))))

(deftest oversize-version-files-are-not-read
  (let [dir (fs/create-temp-dir)]
    (try
      (spit (str (fs/path dir ".java-version")) (str "17\n" (apply str (repeat (inc inv/max-file-bytes) " "))))
      (is (nil? (inv/java-version dir)))
      (spit (str (fs/path dir ".java-version")) "17\n")
      (is (= [".java-version" "17"] (inv/java-version dir)))
      (fs/delete (fs/path dir ".java-version"))
      (fs/create-dirs (fs/path dir ".github" "workflows"))
      (spit (str (fs/path dir ".github" "workflows" "ci.yml"))
            (str "java-version: 21\n" (apply str (repeat (inc inv/max-file-bytes) " "))))
      (is (nil? (inv/java-version dir)))
      (finally (fs/delete-tree dir)))))
