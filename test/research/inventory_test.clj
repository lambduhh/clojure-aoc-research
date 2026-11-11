(ns research.inventory-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [research.inventory :as inv]))

(deftest clojure-lines
  (let [text "(ns aoc.y2021.day07)\n\n;; a comment\n(defn f [x]\n  (inc x))\n\n(comment\n  (f 1)\n  )\n"]
    (is (= {:file/lines 9 :file/code-lines 6 :file/code-lines-no-comment-forms 3}
           (inv/count-lines :clojure text)))
    (is (nil? (:file/code-lines-no-comment-forms (inv/count-lines :clojure "(defn broken [")))
        "unparseable -> nil, not a wrong number")))

(deftest other-languages
  (is (= 2 (:file/code-lines (inv/count-lines :elixir "# hi\ndefmodule A do\n\n  # x\nend\n"))))
  (is (= 2 (:file/code-lines (inv/count-lines :zig "// a\n/// doc\nconst a = 1;\n\npub fn main() void {}\n"))))
  (is (= "let x = 1 \n\nlet y = 2" (inv/strip-ocaml-comments "let x = 1 (* a (* nested *) b *)\n\nlet y = 2")))
  (is (= 2 (:file/code-lines (inv/count-lines :ocaml "(* header\n   spans lines *)\nlet x = 1\n\nlet y = 2\n")))))

(deftest module-names-and-runtime
  (is (= "aoc.y2021.day07" (inv/module-name :clojure "(ns aoc.y2021.day07\n  (:require [clojure.string :as str]))")))
  (is (= "aoc.core" (inv/module-name :clojure "(ns ^:no-doc aoc.core)")))
  (is (= "AoC2021.Day07" (inv/module-name :elixir "defmodule AoC2021.Day07 do\nend")))
  (is (= :bb (inv/runtime "clj" "#!/usr/bin/env bb\n(println 1)")))
  (is (= :jvm (inv/runtime "clj" "(ns a)"))))

(deftest templates
  (testing "same content (digits normalised) for 2+ days in one repo = template"
    (let [files [{:file/path "src/day01.zig" :file/fingerprint "A" :puzzle/keys #{[2023 1]}}
                 {:file/path "src/day02.zig" :file/fingerprint "A" :puzzle/keys #{[2023 2]}}
                 {:file/path "src/day03.zig" :file/fingerprint "B" :puzzle/keys #{[2023 3]}}]]
      (is (= [true true false] (map :file/template? (inv/mark-templates files))))))
  (is (= (inv/fingerprint "pub fn main() void { day(1); }") (inv/fingerprint "pub fn main() void {\n  day(23); }"))))

