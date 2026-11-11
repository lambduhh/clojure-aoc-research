(ns research.sample
  "Draw the mapper-precision sample: per language, 30 files the mapper mapped
  and 10 it didn't (role :util, or :solution without a year). Deterministic:
  files are sorted by key, then shuffled with a fixed seed.
  Writes data/labels/mapper-sample.edn."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [research.cache :as cache]))

(def seed 20261110)
(def per-lang {:mapped 30 :unmapped 10})

(defn- seeded-shuffle [xs]
  (let [al (java.util.ArrayList. ^java.util.Collection xs)]
    (java.util.Collections/shuffle al (java.util.Random. seed))
    (vec al)))

(defn -main [& _]
  (let [repos (into {} (map (juxt :repo/id identity)) (edn/read-string (slurp "data/repos.edn")))
        rows  (for [f (fs/glob (cache/path "extract") "*/*.edn")
                    :let [doc (edn/read-string {:default tagged-literal} (slurp (str f)))]
                    :when (= :ok (:extract/status doc))
                    file (:repo/files doc)
                    :when (and (= :ok (:file/status file)) (= (:file/lang file) (:repo/lang doc))
                               (#{:solution :util} (:file/role file)))]
                {:repo/id (:repo/id doc) :repo/lang (:repo/lang doc)
                 :repo/full-name (get-in repos [(:repo/id doc) :repo/full-name])
                 :repo/created-at (get-in repos [(:repo/id doc) :repo/created-at])
                 :file/path (:file/path file)
                 :mapper (select-keys file [:puzzle/keys :puzzle/source :file/role])})
        pick  (fn [lang mapped? n]
                (->> rows
                     (filter #(and (= lang (:repo/lang %)) (= mapped? (boolean (seq (get-in % [:mapper :puzzle/keys]))))))
                     (sort-by (juxt :repo/id :file/path))
                     seeded-shuffle
                     (take n)))
        sample (vec (map-indexed (fn [i r] (assoc r :sample/id (inc i)))
                                 (for [lang [:clojure :elixir :ocaml :zig]
                                       [mapped? n] [[true (:mapped per-lang)] [false (:unmapped per-lang)]]
                                       r (pick lang mapped? n)]
                                   r)))]
    (spit "data/labels/mapper-sample.edn"
          (str ";; Mapper precision sample, seed " seed ". Labels are in mapper-labels.edn.\n[\n"
               (str/join "\n" (map pr-str sample)) "\n]\n"))
    (println (count sample) "files ->" "data/labels/mapper-sample.edn"
             (frequencies (map (juxt :repo/lang (comp boolean seq :puzzle/keys :mapper)) sample)))))
