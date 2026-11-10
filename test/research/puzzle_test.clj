(ns research.puzzle-test
  (:require [clojure.test :refer [deftest is are testing]]
            [research.puzzle :as puzzle]))

(defn mapped [path & {:as more}]
  (puzzle/map-file (merge {:path path :repo-name "advent-of-code" :created-at "2019-06-01T00:00:00Z"} more)))

(deftest year-and-day-from-path
  (are [path year day] (= #{[year day]} (:puzzle/keys (mapped path)))
    "src/aoc2021/day07.clj"          2021 7
    "src/advent/2018/day_7.clj"      2018 7
    "2022/day-12/solution.ex"        2022 12
    "lib/y2016/d03.ex"               2016 3
    "src/aoc/aoc22/day25.ml"         2022 25
    "2020/07.clj"                    2020 7
    "src/2023_14.zig"                2023 14))

(deftest day-from-directory
  (testing "day in a directory name; year only from an in-season creation date"
    (is (= #{[2019 1]} (:puzzle/keys (mapped "src/day01/main.zig" :created-at "2019-12-01T06:00:00Z"))))
    (is (empty? (:puzzle/keys (mapped "src/day01/main.zig"))) "created in June: no year evidence")))

(deftest year-sources
  (testing "namespace or module name"
    (is (= {:puzzle/keys #{[2017 4]} :puzzle/source :ns}
           (select-keys (mapped "src/day04.clj" :ns-name "aoc.y2017.day04") [:puzzle/keys :puzzle/source]))))
  (testing "repo name"
    (is (= {:puzzle/keys #{[2020 9]} :puzzle/source :repo-name}
           (select-keys (mapped "src/day09.clj" :repo-name "advent-of-code-2020") [:puzzle/keys :puzzle/source]))))
  (testing "created in season: Dec counts as that year, Jan as the previous year"
    (is (= #{[2021 3]} (:puzzle/keys (mapped "src/day03.clj" :repo-name "aoc" :created-at "2021-12-02T10:00:00Z"))))
    (is (= #{[2021 3]} (:puzzle/keys (mapped "src/day03.clj" :repo-name "aoc" :created-at "2022-01-15T10:00:00Z"))))
    (is (= :created-at (:puzzle/source (mapped "src/day03.clj" :repo-name "aoc" :created-at "2021-12-02T10:00:00Z")))))
  (testing "out of season and no other signal: unmapped"
    (is (empty? (:puzzle/keys (mapped "src/day03.clj" :repo-name "aoc" :created-at "2021-06-02T10:00:00Z"))))))

(deftest more-day-spellings
  (are [path year day] (= #{[year day]} (:puzzle/keys (mapped path)))
    "src/aoc_2018/two.clj"                   2018 2
    "2017/src/advent_of_code/day_two.clj"    2017 2
    "2017/src/day-twenty-one.clj"            2017 21
    "src/advent_of_code_2018/dec_6.clj"      2018 6
    "2019/src/advent2019/dec10.clj"          2019 10
    "src/advent_of_code2018/d1p0.cljs"       2018 1)
  (are [path] (empty? (:puzzle/keys (mapped path)))
    "src/aoc2018/d3js.clj" "src/aoc2018/decode.clj" "src/aoc2018/someone.clj" "src/aoc2018/core.clj"))

(deftest invalid-days
  (is (empty? (:puzzle/keys (mapped "src/aoc2021/day26.clj"))))
  (is (empty? (:puzzle/keys (mapped "src/aoc2025/day13.clj"))) "2025 had 12 days")
  (is (= #{[2025 12]} (:puzzle/keys (mapped "src/aoc2025/day12.clj"))))
  (is (empty? (:puzzle/keys (mapped "src/2015/today.clj"))) "'today' is not 'day'"))

(deftest roles
  (are [path role] (= role (:file/role (mapped path)))
    "src/aoc2021/day07.clj"            :solution
    "test/aoc2021/day07_test.clj"      :test
    "test/day07_test.exs"              :test
    "src/aoc2021/utils.clj"            :util
    "src/aoc2021/day07.clj"            :solution)
  (testing "tests still map to their puzzle"
    (is (= #{[2021 7]} (:puzzle/keys (mapped "test/aoc2021/day07_test.clj"))))))
