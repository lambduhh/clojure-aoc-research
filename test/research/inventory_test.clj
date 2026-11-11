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

(deftest deps-edn
  (is (= [{:dep/coord "org.clojure/clojure" :decl/version "1.11.1" :decl/scope :main}
          {:dep/coord "medley/medley" :decl/version "1.4.0" :decl/scope :main}
          {:dep/coord "io.github.cognitect-labs/test-runner" :decl/version "v0.5.1" :decl/scope :test}]
         (inv/deps-edn-deps "{:deps {org.clojure/clojure {:mvn/version \"1.11.1\"} medley {:mvn/version \"1.4.0\"}}
                              :aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}}}}")))
  (is (= :research.inventory/unreadable (inv/deps-edn-deps "{:deps"))))

(deftest project-clj
  (is (= [{:dep/coord "org.clojure/clojure" :decl/version "1.10.1" :decl/scope :main}
          {:dep/coord "instaparse/instaparse" :decl/version "1.4.10" :decl/scope :main}
          {:dep/coord "criterium/criterium" :decl/version "0.4.6" :decl/scope :profile-dev}]
         (inv/project-clj-deps "(defproject aoc \"0.1.0\" :description \"x\"
                                  :dependencies [[org.clojure/clojure \"1.10.1\"] [instaparse \"1.4.10\"]]
                                  :profiles {:dev {:dependencies [[criterium \"0.4.6\"]]}})")))
  (testing "never evaluated: read-eval and unquote are not run"
    (let [marker (str (fs/create-temp-file))]
      (fs/delete marker)
      (inv/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a \"1\"]] :x #=(spit \"" marker "\" \"pwned\"))"))
      (inv/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a ~(spit \"" marker "\" \"pwned\")]])"))
      (is (not (fs/exists? marker))))))

(deftest mix-comments-are-ignored
  (is (= [{:dep/coord "phoenix_live_view" :decl/version "~> 1.0.0-rc" :decl/scope :main}]
         (inv/mix-exs-deps "  # TODO bump on release to {:phoenix_live_view, \"~> 1.0.0\"},\n  {:phoenix_live_view, \"~> 1.0.0-rc\", override: true}, # trailing\n"))))

(deftest templates
  (testing "same content (digits normalised) for 2+ days in one repo = template"
    (let [files [{:file/path "src/day01.zig" :file/fingerprint "A" :puzzle/keys #{[2023 1]}}
                 {:file/path "src/day02.zig" :file/fingerprint "A" :puzzle/keys #{[2023 2]}}
                 {:file/path "src/day03.zig" :file/fingerprint "B" :puzzle/keys #{[2023 3]}}]]
      (is (= [true true false] (map :file/template? (inv/mark-templates files))))))
  (is (= (inv/fingerprint "pub fn main() void { day(1); }") (inv/fingerprint "pub fn main() void {\n  day(23); }"))))

(deftest other-build-files
  (is (= [{:dep/coord "libgraph" :decl/version "~> 0.16" :decl/scope :main}]
         (inv/mix-exs-deps "defp deps do\n  [{:libgraph, \"~> 0.16\"}]\nend")))
  (is (= ["ziglyph" "clap"] (map :dep/coord (inv/zon-deps ".{ .name = \"aoc\", .dependencies = .{ .ziglyph = .{ .url = \"x\" }, .clap = .{ .url = \"y\" } } }"))))
  (is (= ["dune" "containers"] (map :dep/coord (inv/opam-deps "depends: [\n  \"dune\" {>= \"3.0\"}\n  \"containers\"\n]")))))
