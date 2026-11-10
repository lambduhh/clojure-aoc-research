(ns research.puzzle
  "Map a source file to the Advent of Code puzzle(s) it solves: [year day].

  The day comes from the file path. The year comes from the first of these
  that has one, recorded as :puzzle/source:
    :path       2021/, aoc2021, y2021, aoc21, 2021_07
    :ns         (ns aoc.y2021.day07), defmodule AoC2021.Day07
    :repo-name  advent-of-code-2021
    :created-at repo created 15 Nov - 31 Jan, counted as that December's year
                (the weakest signal)"
  (:require [clojure.string :as str]))

(def first-year 2015)
(def last-year 2025)

(defn days-in [year] (if (>= year 2025) 12 25))

(defn- year-in
  "A 4- or 2-digit AoC year inside s, or nil."
  [s]
  (when s
    (or (some->> (re-find #"(?<!\d)(20(?:1[5-9]|2[0-5]))(?!\d)" s) second parse-long)
        (some->> (re-find #"(?i)(?:aoc|advent|y|year)[_-]?(1[5-9]|2[0-5])(?!\d)" s) second parse-long (+ 2000)))))

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

(def ^:private day-patterns
  ;; Tried against the file name, then each directory from the deepest up.
  [#"(?i)(?<![a-z])day[_-]?0?([1-9]|1\d|2[0-5])(?!\d)"
   #"(?i)(?<![a-z])dec[_-]?0?([1-9]|1\d|2[0-5])(?!\d)"
   #"(?i)(?<![a-z])d0?([1-9]|1\d|2[0-5])(?!\d)(?![a-oq-z])"   ; d07, d1p2 (part), not d3js
   #"(?<!\d)20(?:1[5-9]|2[0-5])[_-](0?[1-9]|1\d|2[0-5])(?!\d)"])

(def ^:private bare-number #"^0?([1-9]|1\d|2[0-5])(?:\.[a-z]+)?$")

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
        (some-> (re-find bare-number segment) second parse-long)
        (spelled-day base))))

(defn- segments [path] (reverse (str/split path #"/")))

(defn- day-from-path [path]
  (some day-in-segment (segments path)))

(defn- role [path]
  (let [p (str/lower-case path)]
    (cond
      (or (re-find #"(^|/)(test|tests|spec)/" p)
          (re-find #"(_test|_spec|-test|\.test)\.[a-z]+$" p)) :test
      (day-from-path path)                                    :solution
      :else                                                   :util)))

(defn map-file
  "{:path :ns-name :repo-name :created-at} ->
   {:puzzle/keys #{[year day]} :puzzle/source kw-or-nil :file/role kw}"
  [{:keys [path ns-name repo-name created-at]}]
  (let [day    (day-from-path path)
        [source year] (some (fn [[src y]] (when y [src y]))
                            [[:path (year-in (str/join "/" (butlast (str/split path #"/"))))]
                             [:path (year-in (last (str/split path #"/")))]
                             [:ns (year-in ns-name)]
                             [:repo-name (year-in repo-name)]
                             [:created-at (season-year created-at)]])
        valid? (and day year (<= first-year year last-year) (<= 1 day (days-in year)))]
    {:puzzle/keys   (if valid? #{[year day]} #{})
     :puzzle/source (when valid? source)
     :file/role     (role path)}))
