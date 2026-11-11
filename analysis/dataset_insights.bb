;; Dataset-level insights: AoC repo trend by language, and Thiago's follow-on method replicated.
(require '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str] '[research.names :as names])
(def langs [:clojure :elixir :ocaml :zig])
(defn rows [lang]
  (let [f (str (System/getProperty "user.home") "/aoc-research-cache/datasets/" (name lang) "/" (name lang) ".json")
        data (json/parse-stream (clojure.java.io/reader f) true)]
    (vals (into {} (map (juxt :id identity)) (if (map? data) (val (first data)) data)))))
(defn ym [s] [(parse-long (subs s 0 4)) (parse-long (subs s 5 7))])
(println "== I1: AoC-named repos created per year, by language (dataset, deduped)")
(def per-lang (into {} (for [l langs] [l (rows l)])))
(def years (range 2015 2026))
(println "year " (str/join "  " (map #(format "%7s" (name %)) langs)) "  clojure-share")
(doseq [y years]
  (let [cs (for [l langs] (count (filter #(and (names/aoc-repo? (:full_name %)) (= y (first (ym (:created_at %))))) (per-lang l))))]
    (println y (str/join "  " (map #(format "%7d" %) cs)) (format "   %5.1f%%" (* 100.0 (/ (first cs) (max 1 (reduce + cs))))))))
(println "\n== T2 replicated (Thiago's method): owners with an AoC-named repo created Nov 15-Jan 31,")
(println "   who created a NON-AoC repo in the same language within the following 12 months (Feb 1 .. Jan 31)")
(println "   season  " (str/join "  " (map #(format "%16s" (name %)) langs)))
(doseq [y (range 2015 2025)]
  (let [cells (for [l langs]
                (let [rs (per-lang l)
                      in-season? (fn [r] (let [[yy mm] (ym (:created_at r)) d (parse-long (subs (:created_at r) 8 10))]
                                           (or (and (= yy y) (or (= mm 12) (and (= mm 11) (>= d 15)))) (and (= yy (inc y)) (= mm 1)))))
                      owners (set (keep #(when (and (names/aoc-repo? (:full_name %)) (in-season? %)) (:owner_login %)) rs))
                      after? (fn [r] (let [c (subs (:created_at r) 0 10)] (and (pos? (compare c (str (inc y) "-01-31"))) (neg? (compare c (str (+ y 2) "-02-01"))))))
                      later (set (keep #(when (and (owners (:owner_login %)) (not (names/aoc-repo? (:full_name %))) (after? %)) (:owner_login %)) rs))]
                  (format "%4d/%-5d %5.1f%%" (count later) (count owners) (* 100.0 (/ (count later) (max 1 (count owners)))))))]
    (println "  " y "  " (str/join "  " cells))))
(println "   (exploratory; not pre-registered. 'Non-AoC repo' = name doesn't match research.names/aoc-repo?; only repos whose PRIMARY language is that language are visible)")
