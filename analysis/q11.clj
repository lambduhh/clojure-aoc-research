;; Question 11: is Clojure shorter? Exactly as pre-registered in
;; docs/prereg/lines-of-code.md (question 11, amendments 2 and 4).
;;
;; clojure -M:cloc            # first: <cache>/cloc/counts.edn
;; clojure -M -i analysis/q11.clj
(require '[clojure.edn :as edn] '[clojure.string :as str]
         '[datomic.client.api :as d] '[research.db :as db]
         '[research.cloc :as cloc] '[research.stats :as s])

(def dbv (d/db (d/connect (db/client) {:db-name "research"})))
(def cloc-out (edn/read-string (slurp (cloc/out-file))))
(def langs [:clojure :elixir :ocaml :zig])
(def others [:elixir :ocaml :zig])

;; --- solutions -------------------------------------------------------------
;; Same inclusion rule as research.cloc/included-files-query.
(def file-rows
  (d/q '[:find ?key ?r ?lang ?y ?day ?pl ?nocf
         :where [?f :file/role :solution] [?f :file/status :ok] [?f :file/puzzles ?p]
                (not [?f :file/template? true])
                [?f :file/repo ?r] [?r :repo/lang ?lang] [?f :file/lang ?lang]
                [?f :file/key ?key] [?p :puzzle/year ?y] [?p :puzzle/day ?day]
                [(get-else $ ?f :file/code-lines -1) ?pl]
                [(get-else $ ?f :file/code-lines-no-comment-forms -1) ?nocf]] dbv))

(def counts (:counts cloc-out))

(def solutions
  "One per [repo puzzle]: summed measures, or a :drop reason."
  (for [[[r lang y day] fs] (group-by (fn [[_ r lang y day]] [r lang y day]) file-rows)]
    (let [keys (map first fs)
          missing (remove counts keys)]
      (merge {:repo r :lang lang :puzzle [y day]}
             (cond
               (some #(str/ends-with? % ".livemd") missing) {:drop :livemd}
               (seq missing) {:drop :uncounted-other}
               :else
               (let [c (reduce + (map counts keys))
                     pl (reduce + (map #(nth % 5) fs))
                     ;; No-comment-forms count exists for Clojure files; other
                     ;; languages keep the pipeline count in robustness check 2.
                     nocf (if (= lang :clojure)
                            (reduce + (map #(let [v (nth % 6)] (if (neg? v) (nth % 5) v)) fs))
                            pl)]
                 (cond (zero? c) {:drop :zero-lines}
                       (> c 2000) {:drop :over-2000}
                       :else {:cloc c :pipeline pl :clj-no-comment-forms nocf})))))))

(def kept (remove :drop solutions))

(println "== Funnel: solutions ([repo puzzle] pairs) by language")
(println "   lang      solutions   livemd  uncounted-other  zero-lines  over-2000      kept")
(doseq [l langs
        :let [ss (filter #(= l (:lang %)) solutions)
              n (fn [reason] (count (filter #(= reason (:drop %)) ss)))]]
  (println (format "   %-8s %10d %8d %16d %11d %10d %9d" (name l) (count ss)
                   (n :livemd) (n :uncounted-other) (n :zero-lines) (n :over-2000)
                   (count (remove :drop ss)))))
(println (format "   cloc %s, args %s; %d of %d included files counted"
                 (:cloc-version cloc-out) (str/join " " (:args cloc-out))
                 (count counts) (+ (count counts) (count (:uncounted cloc-out)))))

;; --- per-puzzle ratios -----------------------------------------------------
(def by-lang-puzzle (group-by (juxt :lang :puzzle) kept))

(defn eligible-puzzles [l]
  (sort (for [p (distinct (map :puzzle kept))
              :when (and (>= (count (by-lang-puzzle [:clojure p])) 10)
                         (>= (count (by-lang-puzzle [l p])) 10))]
          p)))

(defn histogram [logs]
  (let [lo -2.0 w 0.2 nb 20
        bin #(-> (/ (- % lo) w) Math/floor long (max 0) (min (dec nb)))
        fr (frequencies (map bin logs))
        top (apply max 1 (vals fr))
        used (keys fr)]
    (str/join "\n"
              (for [b (range (apply min used) (inc (apply max used)))]
                (format "     %5.1f..%5.1f %4d %s" (+ lo (* b w)) (+ lo (* (inc b) w)) (get fr b 0)
                        (apply str (repeat (Math/round (* 40.0 (/ (get fr b 0) top))) "#")))))))

(defn format-p
  "The normal-tail approximation is accurate to ~1.5e-7 absolute, so smaller
  p-values are reported as a bound, not a number."
  [p] (if (< p 1e-6) "< 1e-6" (format "%.2g" p)))

(defn report [label logs seed]
  (let [n (count logs)
        gm (s/geomean-of-logs logs)
        [lo hi] (s/bootstrap-geomean-ci (java.util.Random. seed) 10000 logs)
        w (s/wilcoxon-signed-rank logs)
        dd (s/cohens-d logs)]
    (println (format "   %-24s puzzles %3d  geomean r %.3f [95%% CI %.3f-%.3f]  r<1 %5.1f%%  Wilcoxon z %6.2f p %s (%s)  d %.2f (%s)  H1 %s"
                     label n gm lo hi (* 100.0 (/ (count (filter neg? logs)) n))
                     (:z w) (format-p (:p w)) (s/p-band (:p w)) dd (s/d-band dd)
                     (if (and (< gm 1.0) (< (:p w) 0.05)) "supported" "not supported")))
    (when (pos? (:zeros-dropped w))
      (println (format "   %-24s (%d puzzles with r = 1 dropped from Wilcoxon)" "" (:zeros-dropped w))))))

(defn log-ratios [l measure ps]
  (vec (for [p ps]
         (Math/log (/ (s/median (map measure (by-lang-puzzle [:clojure p])))
                      (s/median (map measure (by-lang-puzzle [l p]))))))))

(println "\n== Q11 primary: median cloc code lines per puzzle, r = median_clojure / median_L")
(doseq [l others :let [ps (eligible-puzzles l)]]
  (report (str "Clojure vs " (name l)) (log-ratios l :cloc ps) 20261117))

(println "\n== Robustness 1: pipeline code lines (:file/code-lines), same solutions")
(doseq [l others :let [ps (eligible-puzzles l)]]
  (report (str "Clojure vs " (name l)) (log-ratios l :pipeline ps) 20261117))

(println "\n== Robustness 2: Clojure without top-level (comment ...) forms; L keeps the pipeline count")
(doseq [l others :let [ps (eligible-puzzles l)]]
  (report (str "Clojure vs " (name l)) (log-ratios l :clj-no-comment-forms ps) 20261117))

(println "\n== Robustness 3 (tokens) and 4 (load check): not run; see amendment 4")

(println "\n== Also reported: equal-size minimum (10 per language, 1,000 draws, seed 20261118), cloc")
(doseq [l others
        :let [ps (eligible-puzzles l)
              rng (java.util.Random. 20261118)
              logs (vec (for [p ps]
                          (let [mc (s/equal-size-min rng 1000 10 (mapv :cloc (by-lang-puzzle [:clojure p])))
                                ml (s/equal-size-min rng 1000 10 (mapv :cloc (by-lang-puzzle [l p])))]
                            (Math/log (/ mc ml)))))]]
  (report (str "Clojure vs " (name l) " (min)") logs 20261117))

(println "\n== Distribution of log r, primary measure (negative = Clojure shorter)")
(doseq [l others]
  (println (str "   Clojure vs " (name l)))
  (println (histogram (log-ratios l :cloc (eligible-puzzles l)))))

(println "\n== Known biases: published solutions only; solver populations differ (OCaml and Zig likely enthusiasts);"
         "\n   golfed and (since 2022) possibly AI-written solutions; Zig least like-for-like; 10 MB clone cap skips"
         "\n   more OCaml and Zig repos; Livebook solutions dropped (Elixir). Mapper precision 95.8% on the holdout;"
         "\n   AI labels behind it still pending a human spot-check.")
(shutdown-agents)
