(ns research.sample
  "Draw the mapper-precision sample: per language, 30 files the mapper mapped
  and 10 it didn't (role :util, or :solution without a year). Deterministic:
  files are sorted by key, then shuffled with a fixed seed.
  Writes data/labels/mapper-sample.edn."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [research.cache :as cache]))

(def default-seed 20261110)
(def per-lang
  ;; :included = exactly what questions 11-13 use (mapped :solution, not a template)
  {:included 30 :unmapped 10 :template 10})

(defn stratum [{:keys [mapper]}]
  (let [{:puzzle/keys [keys] :file/keys [role template?]} mapper]
    (cond
      template?                                 :template
      (and (= :solution role) (seq keys))       :included
      (empty? keys)                             :unmapped)))

(defn- seeded-shuffle [seed xs]
  (let [al (java.util.ArrayList. ^java.util.Collection xs)]
    (java.util.Collections/shuffle al (java.util.Random. (long seed)))
    (vec al)))

(defn -main
  "Flags: --seed N (default 20261110), --out PATH (default data/labels/mapper-sample.edn)."
  [& args]
  (let [opt   (fn [flag] (second (drop-while #(not= flag %) args)))
        seed  (or (some-> (opt "--seed") parse-long) default-seed)
        out   (or (opt "--out") "data/labels/mapper-sample.edn")
        repos (into {} (map (juxt :repo/id identity)) (edn/read-string (slurp "data/repos.edn")))
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
                 :mapper (select-keys file [:puzzle/keys :puzzle/source :file/role :file/template? :file/promoted-from])})
        pick  (fn [lang s n]
                (->> rows
                     (filter #(and (= lang (:repo/lang %)) (= s (stratum %))))
                     (sort-by (juxt :repo/id :file/path))
                     (seeded-shuffle seed)
                     (take n)))
        sample (vec (map-indexed (fn [i r] (assoc r :sample/id (inc i)))
                                 (for [lang [:clojure :elixir :ocaml :zig]
                                       [s n] per-lang
                                       r (pick lang s n)]
                                   (assoc r :sample/stratum s))))]
    (spit out
          (str ";; Mapper precision sample, seed " seed ". Labels are in mapper-labels.edn.\n[\n"
               (str/join "\n" (map pr-str sample)) "\n]\n"))
    (println (count sample) "files ->" out
             (frequencies (map (juxt :repo/lang :sample/stratum) sample)))))
