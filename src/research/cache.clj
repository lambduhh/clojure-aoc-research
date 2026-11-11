(ns research.cache
  "Where downloaded datasets and clones live: outside the repo, never committed.
  Override with AOC_RESEARCH_CACHE."
  (:require [babashka.fs :as fs]))

(defn root []
  (str (or (System/getenv "AOC_RESEARCH_CACHE")
           (fs/path (fs/home) "aoc-research-cache"))))

(defn path [& parts] (str (apply fs/path (root) parts)))

(defn repo-dir
  "Clones are keyed by GitHub's numeric repo id, never by a name from the data."
  [{:repo/keys [lang id]}]
  (when-not (and (keyword? lang) (pos-int? id))
    (throw (ex-info "Bad repo key" {:lang lang :id id})))
  (path "repos" (name lang) (str id)))
