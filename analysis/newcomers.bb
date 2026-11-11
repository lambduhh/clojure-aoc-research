(require '[cheshire.core :as json] '[clojure.string :as str] '[research.names :as names])
(def langs [:clojure :elixir :ocaml :zig])
(defn rows [lang]
  (let [f (str (System/getProperty "user.home") "/aoc-research-cache/datasets/" (name lang) "/" (name lang) ".json")
        data (json/parse-stream (clojure.java.io/reader f) true)]
    (vals (into {} (map (juxt :id identity)) (if (map? data) (val (first data)) data)))))
(def per-lang (into {} (for [l langs] [l (rows l)])))
(defn d10 [r] (subs (:created_at r) 0 10))
(println "== Newcomers only: owners whose FIRST repo in the language is an AoC-named repo created Nov 15 - Jan 31,")
(println "   who create a non-AoC repo in that language in the following 12 months")
(println "   season  " (str/join "  " (map #(format "%16s" (name %)) langs)))
(doseq [y (range 2015 2025)]
  (let [start (str y "-11-15") end (str (inc y) "-01-31") after-end (str (+ y 2) "-02-01")
        cells (for [l langs]
                (let [rs (per-lang l)
                      by-owner (group-by :owner_login rs)
                      newcomers (set (for [[o os] by-owner
                                           :let [first-r (first (sort-by (juxt :created_at :id) os))] ; full timestamp, then id: deterministic
                                           :when (and o (names/aoc-repo? (:full_name first-r))
                                                      (not (neg? (compare (d10 first-r) start)))
                                                      (not (pos? (compare (d10 first-r) end))))]
                                       o))
                      kept (set (for [o newcomers r (by-owner o)
                                      :when (and (not (names/aoc-repo? (:full_name r)))
                                                 (pos? (compare (d10 r) end)) (neg? (compare (d10 r) after-end)))]
                                  o))]
                  (format "%4d/%-5d %5.1f%%" (count kept) (count newcomers) (* 100.0 (/ (count kept) (max 1 (count newcomers)))))))]
    (println "  " y "  " (str/join "  " cells))))
(println "   (exploratory. 'First repo' = earliest created repo whose PRIMARY language is that language in Thiago's dataset)")
