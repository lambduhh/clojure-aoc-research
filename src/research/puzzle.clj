(ns research.puzzle
  "Map a source file to the Advent of Code puzzle(s) it solves: [year day].

  The day comes from the file path, read segment by segment from the file name
  up through each directory:

    1. A strong day pattern (day07, dec_6, d07, 2023_14, day_two) anywhere in
       the path wins, deepest first. So day05/1.clj is day 5.
    2. Otherwise the deepest weak pattern (07, 5a, 10-factory, Q22, ex1, two).
       If that segment is a part number (1, 2, one, q1, 1_factory in a file
       name; 1 or 2 as a directory) and a weak day sits further up, the day
       further up wins: 2015/07/one.clj is day 7, 10/1/program.exs is day 10.
       Zero-padded 01/02 and day+part forms (1a, 01_2) are days, not parts.

  The year comes from the first source in this list that has one, recorded as
  :puzzle/source. Directories are scanned from the deepest up:

    :path        2021/, aoc2021, y2021, 2021_07       (4-digit, directories then file name)
    :path        y15/, year19/                        (2-digit with a y/year prefix)
    :ns          (ns aoc.y2021.day07), AoC2021.Day07  (4-digit)
    :repo-name   advent-of-code-2021                  (4-digit)
    :path        16/ when a different, deeper segment holds the day (bare 2-digit)
    :path        aoc21/, advent17/                    (2-digit; may be a day)
    :ns, :repo-name                                   (2-digit)
    :created-at  repo created 15 Nov - 31 Jan, counted as that December's year
                 (the weakest signal)

  A 2-digit year needs a word boundary before its prefix, so 'day15' is not
  read as 'y15' (2015). The mapper-precision sample found that bug.

  Roles: :config (build and config files), :test, :runner (Elixir mix tasks),
  :solution (has a day) and :util. Only :solution counts as solution code."
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

(defn- explicit-year2
  "A 2-digit year with a y/year prefix (y15, year19). Unlike aoc21 or advent17,
  which can name a day, the prefix makes it a year."
  [s]
  (some->> s
           (re-find #"(?i)(?<![a-z])(?:y|year)[_-]?(1[5-9]|2[0-5])(?!\d)")
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

;; Both lists are tried against each path segment with its extension removed.
(def ^:private strong-day-patterns
  ;; A prefix or a year says outright that the number is a day.
  (mapv re-pattern
        [(str "(?i)(?<![a-z])day[ _-]?" day-n "(?!\\d)")            ; day07, day_7, Day 5
         (str "(?i)(?<![a-z])dec[_-]?" day-n "(?!\\d)")             ; dec_6, dec10
         (str "(?i)(?<![a-z])d" day-n "(?!\\d)(?![a-oq-z])")        ; d07, d1p2 (part); not d3js
         (str "(?<!\\d)20(?:1[5-9]|2[0-5])[_-]" day-n "(?!\\d)")])) ; 2023_14

(def ^:private weak-day-patterns
  ;; Bare numbers: a day, or sometimes a part number or a year.
  (mapv re-pattern
        [(str "(?i)(?<![a-z])(?:q|ex)[_-]?" day-n "(?!\\d)")        ; Q22, ex1
         (str "^" day-n "(?:[ab]|[._-](?:part)?[12])?$")            ; 07, 5a, 6b, 01_2, 23.1
         (str "^" day-n "[-_][A-Za-z][\\w-]*$")]))                  ; 10-factory, 07-handy-haversacks

(def ^:private number-words
  (zipmap ["one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
           "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen" "seventeen"
           "eighteen" "nineteen" "twenty" "twenty-one" "twenty-two" "twenty-three"
           "twenty-four" "twenty-five"]
          (range 1 26)))

(defn- strip-ext [segment] (str/replace segment #"\.[A-Za-z]+$" ""))

(defn- first-match [patterns base]
  (some #(some-> (re-find % base) second parse-long) patterns))

(defn- strong-day
  "Day from a segment with a day/dec/d prefix or a YYYY_DD form, including
  spelled ones (day_two, day-twenty-one); nil if none."
  [segment]
  (let [base (strip-ext segment)]
    (or (first-match strong-day-patterns base)
        (some->> (str/replace (str/lower-case base) "_" "-")
                 (re-find #"(?<![a-z])day-?((?:twenty-)?[a-z]+)$") second number-words))))

(defn- weak-day
  "Day from a bare-number segment (07, 5a, 10-factory, Q22, ex1) or a segment
  that is just a number word (two); nil if none."
  [segment]
  (let [base (strip-ext segment)]
    (or (first-match weak-day-patterns base)
        (number-words (str/replace (str/lower-case base) "_" "-")))))

(defn- part-number?
  "True when a segment reads as part 1 or 2 rather than a day. In a file name:
  1, 2, one, two, q1, ex2, 1_factory. In a directory only a bare 1 or 2:
  directories like 1-Secret-Entrance name days. Zero-padded 01/02 is a day."
  [segment file-name?]
  (let [base (str/lower-case (strip-ext segment))]
    (boolean (if file-name?
               (re-matches #"(?:(?:q|ex)[_-]?)?[12](?:[-_][a-z][\w-]*)?|one|two" base)
               (re-matches #"[12]" base)))))

(defn- segments
  "Path segments from the file name (index 0) up to the top directory."
  [path]
  (vec (reverse (str/split path #"/"))))

(defn- day-from-path
  "[day index] for the segment that supplies the day, index as in `segments`;
  nil if no segment has a day. See the namespace docstring for the rules."
  [path]
  (let [segs  (segments path)
        found (fn [f] (keep-indexed (fn [i s] (when-let [d (f s)] [d i])) segs))]
    ;; Strong first, anywhere: day01/02.ex and d01/q02.exs are day 1, part 2
    ;; (76 extracted files had a zero-padded part under a day/dN directory).
    (or (first (found strong-day))
        ;; One step up only: in src/y15/1/2.clj the 1 is the day.
        (let [[[_ i :as deepest] above] (found weak-day)]
          (if (and above (part-number? (segs i) (zero? i)))
            above
            deepest)))))

(def config-file-names
  #{"build.zig" "build.zig.zon" "mix.exs" ".formatter.exs" "dune" "dune-project"
    "project.clj" "deps.edn" "bb.edn" "shadow-cljs.edn" "build.boot" "config.exs"
    ".iex.exs" ".credo.exs"})

(defn- file-name [path] (last (str/split path #"/")))

(defn config-file?
  "True for build and config files: known names, *.opam, and Elixir .exs
  scripts in a config/ directory (dev.exs, runtime.exs, test.exs)."
  [path]
  (let [f (file-name path)]
    (boolean (or (contains? config-file-names f)
                 (str/ends-with? f ".opam")
                 (re-find #"(^|/)config/[^/]+\.exs$" path)))))

(defn- test-file?
  "True for files in test/ or spec/ directories and for test-named files:
  day07_test.clj, day1.test.exs, test.ml, tests.exs, test_day1.ml,
  puzzle_one_tests.clj. Expects a lower-cased path."
  [p]
  (boolean (or (re-find #"(^|/)(test|tests|spec)/" p)
               (re-find #"([_-]tests?|_spec|\.test)\.[a-z]+$" p)
               (re-find #"(^|/)tests?([_-][\w-]*)?\.[a-z]+$" p))))

(defn- runner-file?
  "True for Elixir mix tasks: lib/mix/tasks/, lib/<app>/mix/tasks/ and the
  singular lib/mix/task/. These mostly read input and call the solution.
  Expects a lower-cased path."
  ;; Path only, so a mix task that holds the whole solution is a runner too:
  ;; 140 extracted files (16 repos) were the only file for their puzzle.
  [p]
  (boolean (re-find #"(^|/)lib/(?:[^/]+/)*mix/tasks?/(?:[^/]+/)*[^/]+\.exs?$" p)))

(defn- role [path day]
  (let [p (str/lower-case path)]
    (cond
      (config-file? path) :config
      (test-file? p)      :test
      (runner-file? p)    :runner
      day                 :solution
      :else               :util)))

(defn- bare-year
  "A directory named exactly 15..25, above the segment that holds the day and
  with a different number, read as a 2-digit year: 16/10-Balance-Bots/ is 2016.
  When the directory is itself the day (16/1.ex, 15/15.zig) it is not a year."
  [segs day day-index]
  (some (fn [s] (when (re-matches #"1[5-9]|2[0-5]" s)
                  (let [n (parse-long s)] (when (not= n day) (+ 2000 n)))))
        (subvec segs (inc day-index))))

(defn- year-and-source [{:keys [path ns-name repo-name created-at]} [day day-index]]
  (let [segs (segments path)
        f    (first segs)
        dirs (rest segs)                    ; deepest first
        in-dirs #(some % dirs)]
    ;; Deepest directory first: in pre2021Work/2020/ or 2020-2021/lib/2021/ the
    ;; deeper, more specific year is the puzzle's.
    ;; y15/ outranks a repo-name year because a repo named aoc2024 can hold
    ;; older years under y15/. A bare 16/ stays below it, since a bare number can
    ;; still be a day; no extracted repo had the two disagree (2026-11-10).
    (some (fn [[src y]] (when y [src y]))
          [[:path (in-dirs year4)] [:path (year4 f)]
           [:path (in-dirs explicit-year2)] [:path (explicit-year2 f)]
           [:ns (year4 ns-name)] [:repo-name (year4 repo-name)]
           [:path (when day (bare-year segs day day-index))]
           [:path (in-dirs year2)] [:path (year2 f)] [:ns (year2 ns-name)] [:repo-name (year2 repo-name)]
           [:created-at (season-year created-at)]])))

(defn map-file
  "{:path :ns-name :repo-name :created-at} ->
   {:puzzle/keys #{[year day]} :puzzle/source kw-or-nil :file/role kw}

  Every role gets :puzzle/keys when the path maps (config files never do);
  :file/role decides whether the file counts as solution code."
  [{:keys [path] :as file}]
  (let [found  (when-not (config-file? path) (day-from-path path))
        day    (first found)
        r      (role path day)
        [source year] (year-and-source file found)
        valid? (and day year (<= first-year year last-year) (<= 1 day (days-in year)))]
    {:puzzle/keys   (if valid? #{[year day]} #{})
     :puzzle/source (when valid? source)
     :file/role     r}))
