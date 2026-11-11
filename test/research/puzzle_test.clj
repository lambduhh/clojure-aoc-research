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

(deftest day-numbers-are-not-years
  (testing "'day15' must not read as y15 = 2015 (found by the labelled sample)"
    (are [path repo year day] (= #{[year day]} (:puzzle/keys (mapped path :repo-name repo)))
      "src/main/day_20.clj"           "advent-2017"          2017 20
      "src/advent_of_code/day20.clj"  "advent-2023-clojure"  2023 20
      "day16/puzzle1.exs"             "advent_of_code_2018"  2018 16
      "bin/Day15.ml"                  "AdventOfCode2023"     2023 15
      "day15/solution.ml"             "aoc2024"              2024 15
      "Day15/day15.zig"               "zig_aoc2020"          2020 15
      "src/day16_2/main.zig"          "advent-of-code-2021"  2021 16
      "src/day25.clj"                 "aoc-2018-clojure"     2018 25
      "day25.zig"                     "advent-of-code-2021"  2021 25))
  (testing "genuine two-digit years still work"
    (are [path year] (= year (ffirst (:puzzle/keys (mapped path :repo-name "x"))))
      "src/aoc22/day05.ml" 2022 "y2016/d03.ex" 2016 "year19/day01.clj" 2019 "src/advent17/day02.clj" 2017)))

(deftest four-digit-years-beat-two-digit-years
  (is (= #{[2020 21]} (:puzzle/keys (mapped "src/aoc21/a/21a.ml" :repo-name "aoc-2020")))))

(deftest more-day-shapes
  (are [path repo year day] (= #{[year day]} (:puzzle/keys (mapped path :repo-name repo)))
    "5a.ex"                     "adventofcode2016"   2016 5
    "2015/6b.zig"               "x"                  2015 6
    "src/01_2.zig"              "adventofcode2020"   2020 1
    "23.1/main.zig"             "aoc2023"            2023 23
    "Day 5/src/parsing.zig"     "advent-of-code-2025" 2025 5
    "10-factory/main.zig"       "AoC-2025"           2025 10
    "lib/Q22/Q22.ex"            "aoc2021"            2021 22
    "src/adventofcode_2019/ex1.clj" "aoc-2019"       2019 1))

(deftest config-files
  (are [path] (= :config (:file/role (mapped path)))
    "11/build.zig" "2023-day3-zig/build.zig" "day1/mix.exs" "2021/elixir/apps/day5/.formatter.exs"
    "day07/dune" "src/day02/project.clj" "build.zig.zon")
  (is (empty? (:puzzle/keys (mapped "2023-day3-zig/build.zig"))) "config files don't map to a puzzle"))

(deftest post-holdout-patterns
  (testing "d11q2 = day 11, question 2 (holdout found d11q2 -> day 2)"
    (is (= #{[2023 11]} (:puzzle/keys (mapped "src/d11q2.zig" :repo-name "ZigAoC2023")))))
  (testing "advent/aoc + a number that can't be a year (1-14) is a day"
    (are [path repo year day] (= #{[year day]} (:puzzle/keys (mapped path :repo-name repo)))
      "2017/advent-10/core.clj"   "x"       2017 10
      "src/AoC/advent4.clj"       "AoC2022" 2022 4
      "aoc04.exs"                 "aoc2021" 2021 4))
  (testing "day-part names like 11-01"
    (are [path year day] (= #{[year day]} (:puzzle/keys (mapped path :repo-name "x")))
      "2017/src/11-01.zig" 2017 11
      "2017/src/16-02.zig" 2017 16))
  (testing "aoc21 stays an ambiguous year, not a day"
    (is (= #{[2021 7]} (:puzzle/keys (mapped "src/aoc21/day07.clj" :repo-name "x"))))))

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

;; ---------------------------------------------------------------------------
;; Adversarial-review findings (mapping-and-counting). Paths below are real
;; paths from the extracted repos unless noted.

(defn- keys-of [path & more] (:puzzle/keys (apply mapped path more)))

(deftest part-numbers-defer-to-the-day   ; finding 3
  (testing "a part number in the file name yields to a day in a directory"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "2015/07/one.clj"                      "x"                    2015 7
      "2020/15/two.clj"                      "x"                    2020 15
      "src/advent_of_code/day5/1.clj"        "aoc-2019"             2019 5
      "2019/22/1.exs"                        "x"                    2019 22
      "d05/q02.exs"                          "aoc-2018"             2018 5
      "day3/ex1.exs"                         "aoc-2018"             2018 3
      "day3/q1.ml"                           "aoc-2018"             2018 3
      "2023/8/2.zig"                         "x"                    2023 8
      "day05/1.clj"                          "aoc-2018"             2018 5
      "2022/19/1_factory.exs"                "x"                    2022 19
      "15/1_wrong.zig"                       "adventofcode-2022"    2022 15
      "16/1.ex"                              "AOC_2022"             2022 16))
  (testing "a part number in a directory yields to a day further up"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "10/1/program.exs"                                   "adventofcode_2021" 2021 10
      "2023/02/1/cube_conundrum/lib/cube_conundrum.ex"     "advent-of-code"    2023 2
      "src/y15/13/1.clj"                                   "aoc2024"           2015 13
      "src/y15/1/2.clj"                                    "aoc2024"           2015 1))
  (testing "a strong day pattern anywhere beats a weak one in the file name"
    ;; 225424618: day2/3.exs defines ProgramAlarm, which is 2019 day 2.
    (is (= #{[2019 2]} (keys-of "day2/3.exs" :repo-name "adventofcode-2019"))))
  (testing "still days, not parts"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "day16/puzzle1.exs"         "advent_of_code_2018"  2018 16  ; precision study
      "2015/6b.zig"               "x"                    2015 6
      "src/aoc/23/03.clj"         "x"                    2023 3   ; not a part number
      "src/aoc/15/01.clj"         "x"                    2015 1   ; zero-padded: a day
      "24/day/01.zig"             "x"                    2024 1
      "19/day17p2.ml"             "x"                    2019 17  ; the file's own strong day
      "src/adventofcode_2019/ex1.clj" "aoc-2019"         2019 1   ; nothing above to defer to
      "25/1-Secret-Entrance/solution.exs" "aoc"          2025 1)))  ; 'N-name' directory is a day

(deftest bare-two-digit-year-directories   ; finding 5
  (testing "15..25 is a year when a different segment supplies the day"
    (are [path year day] (= #{[year day]} (keys-of path :repo-name "aoc" :created-at "2025-11-17T00:00:00Z"))
      "16/10-Balance-Bots/solution.exs"  2016 10
      "23/src/day09.zig"                 2023 9
      "24/src/solutions/day09.zig"       2024 9
      "23/old_solutions/09/src/main.zig" 2023 9
      "19/day1.ml"                       2019 1
      "19/d7/aoc.ml"                     2019 7)
    (is (= :path (:puzzle/source (mapped "19/day1.ml" :repo-name "aoc")))))
  (testing "not when the directory is itself the day"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "lib/16/parser.ex"        "advent-of-code-2021"   2021 16
      "15/1.exs"                "AOC-2024"              2024 15
      "lib/days/15/fifteen.ex"  "advent_of_code_2021"   2021 15
      "15/15.zig"               "advent-of-zig-2024"    2024 15)))

(deftest deepest-four-digit-year-wins   ; finding 6
  (are [path year day] (= #{[year day]} (keys-of path))
    "pre2021Work/2020/src/main/clojure/day_1.clj"  2020 1
    "src/advent_2021/2024/day1.clj"                2024 1
    "2020-2021/lib/2021/1.ex"                      2021 1
    "archive2017-2019/2018/day01/day1.exs"         2018 1))

(deftest explicit-two-digit-years   ; finding 7
  (testing "y15/ and year19/ name a year outright, so they beat a repo-name year"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "src/y15/1/1.clj"     "aoc2024"              2015 1
      "year19/day01.clj"    "advent-of-code-2024"  2019 1
      "lib/y16/day05.ex"    "advent-of-code-2024"  2016 5))
  (testing "aoc21/ and advent17/ may be days, so a repo-name year still wins"
    (are [path repo year day] (= #{[year day]} (keys-of path :repo-name repo))
      "src/aoc21/a/21a.ml"       "aoc-2020"               2020 21
      "src/advent17/day02.clj"   "advent-of-code-2020"    2020 2))
  (testing "a 4-digit path year still beats y15/"
    (is (= #{[2016 3]} (keys-of "2016/y15/day03.clj")))))

(deftest test-file-names   ; finding 8
  (are [path] (= :test (:file/role (mapped path :repo-name "aoc-2021")))
    "day1/tests.ml" "day10/test.ml" "2021/day08/test.exs" "2015/day_01/tests.exs"
    "src/day_2/puzzle_one_tests.clj" "test_day1.ml" "day3/test_day3a.ml"
    "day3/test-code.exs" "day02/2-tests.ex" "day04/solution_tests.exs" "day05/Test.ml")
  (testing "names that merely contain 'test' are not tests"
    (are [path] (= :solution (:file/role (mapped path :repo-name "aoc-2021")))
      "day07/testing.clj" "day07/contest.clj" "day07/latest.ml")))

(deftest runner-and-config-files   ; finding 9
  (testing "Elixir mix tasks are runners, not solutions"
    (are [path] (= :runner (:file/role (mapped path :repo-name "aoc-2022")))
      "lib/mix/tasks/d07.p1.ex" "lib/mix/tasks/d07.ex" "apps/aoc/lib/mix/tasks/d01.ex"
      "2022/01/calorie_counting/lib/mix/tasks/count.ex" "lib/mix/task/day01.p1.ex"
      "lib/aoc/mix/tasks/day05.ex")
    (is (= #{[2022 7]} (keys-of "lib/mix/tasks/d07.p1.ex" :repo-name "aoc-2022"))
        "runners still record their puzzle"))
  (testing "tests of mix tasks are tests; other lib/mix files are not runners"
    (is (= :test (:file/role (mapped "test/mix/tasks/solve_test.exs" :repo-name "aoc-2022"))))
    (is (= :solution (:file/role (mapped "2023/03/1/gear_ratios/lib/mix/solve.ex")))))
  (testing "Elixir config scripts"
    (are [path] (= :config (:file/role (mapped path)))
      "day11/dumbo_octopus/config/dev.exs" "day11/dumbo_octopus/config/runtime.exs"
      "day11/dumbo_octopus/config/test.exs" "2020/day1/.iex.exs" "day_2/.credo.exs"))
  (is (= :solution (:file/role (mapped "src/config/day01.clj" :repo-name "aoc-2021")))
      "only .exs scripts under config/ are config"))
