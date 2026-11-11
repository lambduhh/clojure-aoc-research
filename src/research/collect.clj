(ns research.collect
  "Stage 1: Thiago Carvalho's programming-language-vitality datasets, pinned to
  one commit -> data/repos.edn (AoC-named repos created 2015-2025)."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [research.cache :as cache]
            [research.names :as names])
  (:import (java.security MessageDigest)))

(def dataset-commit "916e0f55bd44afe55382c9925f05cf3e243abfc8")
(def languages [:clojure :elixir :ocaml :zig])
(def years [2015 2025])

(defn dataset-url [lang]
  (str "https://raw.githubusercontent.com/thiagooak/programming-language-vitality/"
       dataset-commit "/data/" (name lang) ".json.zip"))

(defn sha256 [file]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/file file))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn fetch!
  "Download and unzip one dataset into the cache (skipped if already there)."
  [lang]
  (let [dir  (cache/path "datasets" (name lang))
        zip  (str (fs/path dir (str (name lang) ".json.zip")))
        json (str (fs/path dir (str (name lang) ".json")))]
    (fs/create-dirs dir)
    (when-not (fs/exists? zip)
      (let [resp (http/get (dataset-url lang) {:as :stream})]
        (with-open [in (:body resp)] (io/copy in (io/file zip)))))
    (when-not (fs/exists? json)
      (fs/unzip zip dir {:replace-existing true}))
    {:lang lang :url (dataset-url lang) :zip-sha256 (sha256 zip) :json json}))

(defn- created-year [row] (some-> (:created_at row) (subs 0 4) parse-long))

(defn select-repos
  "Deduplicate by id (the datasets repeat some rows), keep AoC-named repos in
  the year range, and drop names that aren't safe to use."
  [lang rows]
  (let [[from to] years]
    (->> rows
         (reduce (fn [m r] (assoc m (:id r) r)) {})
         vals
         (filter #(some-> (created-year %) (<= to)))
         (filter #(>= (created-year %) from))
         (filter #(names/safe-full-name? (:full_name %)))
         (filter #(names/aoc-repo? (:full_name %)))
         (map (fn [r]
                {:repo/id         (:id r)
                 :repo/lang       lang
                 :repo/full-name  (:full_name r)
                 :repo/created-at (:created_at r)
                 :repo/pushed-at  (:pushed_at r)
                 :repo/size-kb    (or (:size r) 0)
                 :repo/stars      (or (:stargazers_count r) 0)
                 :repo/archived?  (boolean (:is_archived r))}))
         (sort-by :repo/id))))

(defn- read-rows [json-file]
  (let [data (json/parse-stream (io/reader json-file) true)]
    (if (map? data) (val (first data)) data)))

(defn- spit-edn-lines
  "A vector with one map per line, so git diffs stay readable."
  [file xs]
  (spit file (str "[\n" (str/join "\n" (map pr-str xs)) "\n]\n")))

(defn -main [& _]
  (fs/create-dirs "data")
  (let [fetched (mapv fetch! languages)
        repos   (vec (mapcat (fn [{:keys [lang json]}] (select-repos lang (read-rows json)))
                             fetched))]
    (spit "data/datasets.edn"
          (pr-str {:dataset-commit dataset-commit
                   :years          years
                   :files          (mapv #(dissoc % :json) fetched)}))
    (spit-edn-lines "data/repos.edn" repos)
    (doseq [[lang n] (frequencies (map :repo/lang repos))]
      (println (format "%-8s %5d repos" (name lang) n)))
    (println "total   " (count repos) "-> data/repos.edn")))
