(ns research.names-test
  (:require [clojure.test :refer [deftest is are]]
            [research.names :as names]))

(deftest aoc-repo
  (are [full-name] (names/aoc-repo? full-name)
    "a/advent-of-code" "a/AdventOfCode" "a/aoc2021" "a/aoc-2021" "a/my-aoc"
    "a/aoc_clj" "a/clj-aoc" "a/AoC" "a/ZigAocTemplate" "a/elixirAoC2023" "a/aoc")
  (are [full-name] (not (names/aoc-repo? full-name))
    "a/kaocha-retry" "a/garden-kaocha-bb-starter" "a/metaocaml_test"
    "a/laocoon" "a/taocupao_app_nerves" "a/ParadygmatyProgramowaniaOCaml"
    "aoc-owner/unrelated"))

(deftest safety
  (is (names/safe-full-name? "lambduhh/clojure-template-aoc"))
  (are [bad] (not (names/safe-full-name? bad))
    "../etc" "a/../b" "a/b/c" "a b/c" nil "a/b;rm")
  (is (= "https://github.com/a/b.git" (names/clone-url "a/b")))
  (is (thrown? Exception (names/clone-url "a/../b"))))
