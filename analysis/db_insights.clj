(require '[datomic.client.api :as d] '[research.db :as db] '[clojure.string :as str])
(def dbv (d/db (d/connect (db/client) {:db-name "research"})))
(defn year-of [^java.util.Date dt] (.getYear (.atZone (.toInstant dt) java.time.ZoneOffset/UTC)))
(defn median [xs] (let [v (vec (sort xs)) n (count v)] (when (pos? n) (if (odd? n) (v (quot n 2)) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0)))))
(defn pct [a b] (format "%5.1f%%" (* 100.0 (/ a (max 1 b)))))
(def langs [:clojure :elixir :ocaml :zig])

(println "== Q13 how far solvers get (included solution files; repo-years; 2015-2024 have 25 days)")
(let [rows (d/q '[:find ?r ?lang ?y ?day
                  :where [?f :file/role :solution] [?f :file/status :ok] [?f :file/puzzles ?p]
                         (not [?f :file/template? true])
                         [?f :file/repo ?r] [?r :repo/lang ?lang] [?f :file/lang ?lang]
                         [?p :puzzle/year ?y] [?p :puzzle/day ?day]] dbv)
      ry (reduce (fn [m [r lang y day]] (update m [r lang y] (fnil conj #{}) day)) {} rows)]
  (doseq [[label yr-ok?] [["2015-2024 (25 days)" #(<= 2015 % 2024)] ["2022-2024 only" #(<= 2022 % 2024)] ["2025 (12 days)" #(= 2025 %)]]]
    (println " " label)
    (println "   lang      repo-years  median-days  median-last-day  reached-last-day  stopped-by-day-7")
    (doseq [l langs
            :let [ds (for [[[_ lang y] days] ry :when (and (= lang l) (yr-ok? y))] days)
                  full (if (= label "2025 (12 days)") 12 25)
                  n (count ds)]]
      (println (format "   %-8s %11d %12s %16s %17s %17s" (name l) n
                       (str (median (map count ds))) (str (median (map #(apply max %) ds)))
                       (pct (count (filter #(contains? % full) ds)) n)
                       (pct (count (filter #(<= (apply max %) 7) ds)) n))))))

(println "\n== Q3 build files in Clojure repos, by creation year (share of cloned Clojure repos)")
(let [repos (d/q '[:find ?r ?c :where [?r :repo/lang :clojure] [?r :repo/clone-status :cloned] [?r :repo/created-at ?c]] dbv)
      bf (reduce (fn [m [r b]] (update m r (fnil conj #{}) b)) {} (d/q '[:find ?r ?b :where [?r :repo/lang :clojure] [?r :repo/build-files ?b]] dbv))
      runtimes (set (map first (d/q '[:find ?r :where [?r :repo/lang :clojure] [?f :file/repo ?r] [?f :file/runtime ?rt] [(contains? #{:bb :nbb} ?rt)]] dbv)))
      by-year (group-by (comp year-of second) repos)]
  (println "   year   n   deps.edn  project.clj  bb.edn  any-bb-script  boot  shadow-cljs")
  (doseq [[y rs] (sort by-year) :when (<= 2015 y 2025)
          :let [n (count rs) has (fn [k] (count (filter #(contains? (bf (first %)) k) rs)))]]
    (println (format "   %d %4d %9s %12s %7s %14s %5s %12s" y n (pct (has :deps-edn) n) (pct (has :project-clj) n) (pct (has :bb-edn) n)
                     (pct (count (filter #(or (contains? (bf (first %)) :bb-edn) (runtimes (first %))) rs)) n) (pct (has :boot) n) (pct (has :shadow-cljs) n)))))

(println "\n== Q18 declared org.clojure/clojure version (main scope), by repo creation year: top minors")
(let [rows (d/q '[:find ?r ?c ?v :where [?r :repo/lang :clojure] [?r :repo/created-at ?c] [?x :decl/repo ?r]
                  [?x :decl/dep ?dep] [?dep :dep/canonical-coord "org.clojure/clojure"] [?x :decl/scope :main] [?x :decl/version ?v]] dbv)
      minor (fn [v] (or (second (re-find #"^1\.(\d+)" v)) "other"))]
  (doseq [[y rs] (sort (group-by (comp year-of second) rows)) :when (<= 2015 y 2025)]
    (println "  " y (count (set (map first rs))) "repos:" (take 4 (sort-by (comp - val) (frequencies (map (comp #(str "1." %) minor last) rs)))))))

(println "\n== Q6 feature adoption: share of Clojure repos (with solutions in that puzzle year) using each var at least once")
(let [usage (d/q '[:find ?r ?y ?fqn
                   :in $ [?fqn ...]
                   :where [?v :var/fqn ?fqn] [?u :usage/var ?v] [?u :usage/file ?f]
                          [?f :file/role :solution] [?f :file/puzzles ?p] [?p :puzzle/year ?y]
                          [?f :file/repo ?r] [?r :repo/lang :clojure]] dbv
                 ["clojure.core/parse-long" "java.lang.Long/parseLong" "java.lang.Integer/parseInt" "clojure.core/read-string" "clojure.edn/read-string"
                  "clojure.core/update-vals" "clojure.core/update-keys" "clojure.core/abs" "clojure.core/partitionv" "clojure.core/iteration" "clojure.math/floor" "clojure.core/frequencies"])
      active (d/q '[:find ?y (count-distinct ?r) :where [?f :file/role :solution] [?f :file/puzzles ?p] [?p :puzzle/year ?y]
                    [?f :file/repo ?r] [?r :repo/lang :clojure] [?f :file/lang :clojure]] dbv)
      act (into {} active)
      by (reduce (fn [m [r y fqn]] (update m [fqn y] (fnil conj #{}) r)) {} usage)
      ys (range 2019 2026)]
  (println "   var" (apply str (repeat 24 " ")) (str/join " " (map #(format "%6d" %) ys)))
  (doseq [fqn ["clojure.core/parse-long" "java.lang.Long/parseLong" "java.lang.Integer/parseInt" "clojure.core/read-string" "clojure.edn/read-string" "clojure.core/update-vals" "clojure.core/abs" "clojure.core/partitionv" "clojure.math/floor"]]
    (println (format "   %-28s" fqn) (str/join " " (map #(pct (count (by [fqn %])) (act %)) ys))))
  (println "   repos with Clojure solutions per puzzle year:" (into (sorted-map) (select-keys act ys))))

(println "\n== Q21 AI tooling files, repos by language (any :repo/ai-files) and by creation year")
(let [rows (d/q '[:find ?r ?lang ?c ?a :where [?r :repo/ai-files ?a] [?r :repo/lang ?lang] [?r :repo/created-at ?c]] dbv)]
  (println "   by lang:" (into (sorted-map) (update-vals (group-by second rows) #(count (set (map first %))))))
  (println "   by creation year:" (into (sorted-map) (update-vals (group-by (comp year-of #(nth % 2)) rows) #(count (set (map first %))))))
  (println "   by file:" (frequencies (map last rows))))

(println "\n== Q22 editor evidence (repos), by language")
(doseq [l langs]
  (println "  " (name l) (sort-by (comp - val) (frequencies (map second (d/q '[:find ?r ?e :in $ ?l :where [?r :repo/lang ?l] [?r :repo/editor-signals ?e]] dbv l))))))

(println "\n== AoC helper libraries in other ecosystems: top declared deps (distinct repos, same-ecosystem only, non-local)")
(doseq [[l eco] [[:elixir :hex] [:ocaml :opam] [:zig :zig]]]
  (let [rows (d/q '[:find ?coord (count-distinct ?r) :in $ ?l ?eco
                    :where [?r :repo/lang ?l] [?x :decl/repo ?r] [?x :decl/local? false] [?x :decl/dep ?dep]
                           [?dep :dep/ecosystem ?eco] [?dep :dep/canonical-coord ?coord]] dbv l eco)
        n (ffirst (d/q '[:find (count ?r) :in $ ?l :where [?r :repo/lang ?l] [?r :repo/clone-status :cloned]] dbv l))]
    (println "  " (name l) "(" n "cloned repos):" (str/join ", " (map (fn [[c k]] (str c " " k)) (take 15 (sort-by (comp - second) rows)))))))

(println "\n== Datomic in AoC repos")
(println "  " (d/q '[:find ?coord (count-distinct ?r) :where [?dep :dep/canonical-coord ?coord] [(clojure.string/starts-with? ?coord "com.datomic")] [?x :decl/dep ?dep] [?x :decl/repo ?r]] dbv))
(shutdown-agents) (System/exit 0)
