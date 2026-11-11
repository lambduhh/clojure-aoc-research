(ns research.puzzle
  "Map a source file to the Advent of Code puzzle(s) it solves: [year day].

  The day comes from the file path: the file name first, then each directory
  from the deepest up. The year comes from the first source that has one,
  recorded as :puzzle/source. Every 4-digit year outranks every 2-digit one:

    :path        2021/, aoc2021, y2021, 2021_07       (4-digit, directories then file name)
    :ns          (ns aoc.y2021.day07), AoC2021.Day07  (4-digit)
    :repo-name   advent-of-code-2021                  (4-digit)
    :path        aoc21/, y16/, year19/                (2-digit)
    :ns, :repo-name                                   (2-digit)
    :created-at  repo created 15 Nov - 31 Jan, counted as that December's year
                 (the weakest signal)

  A 2-digit year needs a word boundary before its prefix, so 'day15' is not
  read as 'y15' (2015). The mapper-precision sample found that bug."
  (:require [clojure.string :as str]))

(def first-year 2015)
(def last-year 2025)

(defn days-in [year] (if (>= year 2025) 12 25))

(defn- year4 [s]
  (some->> s (re-find #"(?<!\d)(20(?:1[5-9]|2[0-5]))(?!\d)") second parse-long))

(defn- year2 [s]
  (some->> s
           (re-find #"(?i)(?<![a-z])(?:aoc|advent|adventofcode|y|year)[_-]?(1[5-9]|2[0-5])(?!\d)")
           second parse-long (+ 2000)))

(defn- season-year
  "Repo created 15 Nov - 31 Dec -> that year; 1 - 31 Jan -> the previous year."
  [created-at]
  (when (and created-at (>= (count created-at) 10))
    (let [y (parse-long (subs created-at 0 4))
          m (parse-long (subs created-at 5 7))
          d (parse-long (subs created-at 8 10))]
      (cond
        (or (= m 12) (and (= m 11) (>= d 15))) y
        (= m 1)                                (dec y)))))

(def ^:private day-n "0?([1-9]|1\\d|2[0-5])")

(def ^:private day-patterns
  ;; Tried against each path segment with its extension removed.
  (mapv re-pattern
        [(str "(?i)(?<![a-z])day[ _-]?" day-n "(?!\\d)")            ; day07, day_7, Day 5
         (str "(?i)(?<![a-z])dec[_-]?" day-n "(?!\\d)")             ; dec_6, dec10
         (str "(?i)(?<![a-z])d" day-n "(?!\\d)(?![a-oq-z])")        ; d07, d1p2 (part); not d3js
         (str "(?i)(?<![a-z])(?:q|ex)[_-]?" day-n "(?!\\d)")        ; Q22, ex1
         (str "(?<!\\d)20(?:1[5-9]|2[0-5])[_-]" day-n "(?!\\d)")    ; 2023_14
         (str "^" day-n "(?:[ab]|[._-](?:part)?[12])?$")            ; 07, 5a, 6b, 01_2, 23.1
         (str "^" day-n "[-_][A-Za-z][\\w-]*$")]))                  ; 10-factory, 07-handy-haversacks

(def ^:private number-words
  (zipmap ["one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
           "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen" "seventeen"
           "eighteen" "nineteen" "twenty" "twenty-one" "twenty-two" "twenty-three"
           "twenty-four" "twenty-five"]
          (range 1 26)))

(defn- spelled-day
  "day_two, day-twenty-one, or a file named just two.clj."
  [base]
  (let [b (str/replace (str/lower-case base) "_" "-")]
    (or (some->> (re-find #"(?<![a-z])day-?((?:twenty-)?[a-z]+)$" b) second number-words)
        (number-words b))))

(defn- strip-ext [segment] (str/replace segment #"\.[A-Za-z]+$" ""))

(defn- day-in-segment [segment]
  (let [base (strip-ext segment)]
    (or (some #(some-> (re-find % base) second parse-long) day-patterns)
        (spelled-day base))))

(defn- day-from-path [path]
  (some day-in-segment (reverse (str/split path #"/"))))

(def config-file-names
  #{"build.zig" "build.zig.zon" "mix.exs" ".formatter.exs" "dune" "dune-project"
    "project.clj" "deps.edn" "bb.edn" "shadow-cljs.edn" "build.boot" "config.exs"})

(defn- file-name [path] (last (str/split path #"/")))

(defn config-file? [path]
  (let [f (file-name path)]
    (or (contains? config-file-names f) (str/ends-with? f ".opam"))))

(defn- role [path]
  (let [p (str/lower-case path)]
    (cond
      (config-file? path)                                     :config
      (or (re-find #"(^|/)(test|tests|spec)/" p)
          (re-find #"(_test|_spec|-test|\.test)\.[a-z]+$" p)) :test
      (day-from-path path)                                    :solution
      :else                                                   :util)))

(defn- year-and-source [{:keys [path ns-name repo-name created-at]}]
  (let [dirs (str/join "/" (butlast (str/split path #"/")))
        f    (file-name path)]
    (some (fn [[src y]] (when y [src y]))
          [[:path (year4 dirs)] [:path (year4 f)] [:ns (year4 ns-name)] [:repo-name (year4 repo-name)]
           [:path (year2 dirs)] [:path (year2 f)] [:ns (year2 ns-name)] [:repo-name (year2 repo-name)]
           [:created-at (season-year created-at)]])))

(defn map-file
  "{:path :ns-name :repo-name :created-at} ->
   {:puzzle/keys #{[year day]} :puzzle/source kw-or-nil :file/role kw}"
  [{:keys [path] :as file}]
  (let [r      (role path)
        day    (when-not (= r :config) (day-from-path path))
        [source year] (year-and-source file)
        valid? (and day year (<= first-year year last-year) (<= 1 day (days-in year)))]
    {:puzzle/keys   (if valid? #{[year day]} #{})
     :puzzle/source (when valid? source)
     :file/role     r}))
