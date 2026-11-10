(ns research.names
  "Which repos count as Advent of Code repos, and where they live on disk."
  (:require [clojure.string :as str]))

(def ^:private aoc-pattern
  ;; "advent" anywhere; "aoc" at a word boundary (aoc2021, my-aoc, aoc_clj);
  ;; or camelCase "Aoc"/"AoC" (ZigAocTemplate). Rejects "aoc" inside words
  ;; such as kaocha, metaocaml, laocoon.
  #"(?i:advent)|(?i:(?<![a-z])aoc)|(?i:aoc(?=[-_.0-9]|$))|Ao[cC](?=[A-Z0-9_.-]|$)|(?<=[a-z])Ao[cC]")

(defn repo-name
  "\"owner/name\" -> \"name\"."
  [full-name]
  (second (str/split full-name #"/" 2)))

(defn aoc-repo?
  "True when the repo *name* (not the owner) looks like an AoC repo."
  [full-name]
  (boolean (some->> (repo-name full-name) (re-find aoc-pattern))))

(def ^:private safe-full-name #"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

(defn safe-full-name?
  "GitHub owner/name with no path tricks (no slashes beyond one, no '..')."
  [full-name]
  (boolean (and (string? full-name)
                (re-matches safe-full-name full-name)
                (not (str/includes? full-name "..")))))

(defn clone-url [full-name]
  (when-not (safe-full-name? full-name)
    (throw (ex-info "Unsafe repo name" {:full-name full-name})))
  (str "https://github.com/" full-name ".git"))
