(ns research.load-test
  "repo-tx as a pure function, plus in-memory Datomic Local transactions for
  the load loop and the --fresh / --append decision."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [research.db :as db]
            [research.load :as load]))

;; ---------------------------------------------------------------------------
;; Helpers

(def aliases (load/read-aliases "data/dep-aliases.edn"))

(def ctx {:core-vars {"clojure.core/parse-long" {:added "1.11"}
                      "clojure.core/some->"     {:added "1.5" :macro true}
                      "clojure.core/*fn-loader*" {:added nil}
                      "clojure.core/keep"       {:added "1.2"}
                      "clojure.core/map"        {:added "1.0"}
                      "clojure.core/doto"       {:added "1.0" :macro true}}
          :aliases aliases})

(defn- repo [id lang & {:as more}]
  (merge {:repo/id id :repo/lang lang :repo/full-name (str "someone/aoc-" id)
          :repo/size-kb 10 :repo/stars 0 :repo/archived? false
          :repo/created-at "2021-12-01T10:00:00Z" :repo/pushed-at "2021-12-25T10:00:00Z"}
         more))

(defn- decl [source eco kind & deps]
  {:decl/source source :decl/status :ok :decl/ecosystem eco :decl/kind kind
   :decl/deps (vec (for [[coord version local?] deps]
                     {:dep/coord coord :decl/version version :decl/scope :main :decl/local? (boolean local?)}))})

(defn- entities-with [k tx] (filter #(contains? % k) tx))

(defn- deps-of [tx] (into {} (map (juxt :dep/key identity)) (entities-with :dep/key tx)))

(defn- mem-client []
  (db/client {:storage-dir :mem :system (str "test-" (random-uuid))}))

(defn- q1 [db query & args] (apply d/q query db args))

;; ---------------------------------------------------------------------------
;; Dependencies: ecosystem from the build file, identity ecosystem:coord

(deftest dep-ecosystem-comes-from-the-build-file
  ;; deps-parsing[0]: a Zig repo with a deps.edn must not relabel
  ;; org.clojure/clojure as :zig.
  (let [tx (load/repo-tx (repo 1 :zig) nil
                         {:repo/declarations [(decl "clj/deps.edn" :maven :package ["org.clojure/clojure" "1.11.1"])
                                              (decl "build.zig.zon" :zig :package ["ziglyph" nil])]}
                         nil ctx)
        deps (deps-of tx)]
    (is (= #{"maven:org.clojure/clojure" "zig:ziglyph"} (set (keys deps))))
    (is (= {:dep/coord "org.clojure/clojure" :dep/ecosystem :maven}
           (select-keys (deps "maven:org.clojure/clojure") [:dep/coord :dep/ecosystem])))
    (is (= :zig (:dep/ecosystem (deps "zig:ziglyph"))))))

(deftest dep-ecosystem-falls-back-to-repo-language
  (let [d (dissoc (decl "mix.exs" :hex :package ["jason" "~> 1.4"]) :decl/ecosystem)
        tx (load/repo-tx (repo 2 :elixir) nil {:repo/declarations [d]} nil ctx)]
    (is (= ["hex:jason"] (keys (deps-of tx))))))

(deftest same-name-in-two-ecosystems-is-two-deps
  ;; deps-parsing[13]: "aoc" on hex and on opam are unrelated packages.
  (let [tx (load/repo-tx (repo 3 :ocaml) nil
                         {:repo/declarations [(decl "aoc.opam" :opam :package ["aoc" nil])
                                              (decl "mix.exs" :hex :package ["aoc" "~> 0.1"])]}
                         nil ctx)
        deps (deps-of tx)]
    (is (= #{"opam:aoc" "hex:aoc"} (set (keys deps))))
    (is (= #{"aoc"} (set (map :dep/coord (vals deps)))))
    (testing "each declaration points at its own ecosystem's dep"
      (is (= #{"d:opam:aoc" "d:hex:aoc"} (set (map :decl/dep (entities-with :decl/key tx))))))))

(deftest canonical-coordinates
  ;; deps-parsing[8]: renamed libraries keep their declared coord and gain
  ;; the canonical one.
  (let [tx (load/repo-tx (repo 4 :clojure) nil
                         {:repo/declarations [(decl "deps.edn" :maven :package
                                                    ["medley/medley" "1.4.0"]
                                                    ["com.cognitect/test-runner" "209b64504"]
                                                    ["instaparse/instaparse" "1.4.12"])]}
                         nil ctx)
        deps (deps-of tx)]
    (is (= "medley/medley" (:dep/coord (deps "maven:medley/medley"))))
    (is (= "dev.weavejester/medley" (:dep/canonical-coord (deps "maven:medley/medley"))))
    (is (= "io.github.cognitect-labs/test-runner"
           (:dep/canonical-coord (deps "maven:com.cognitect/test-runner"))))
    (is (= "instaparse/instaparse" (:dep/canonical-coord (deps "maven:instaparse/instaparse")))
        "coords without an alias are their own canonical coord"))
  (testing "aliases apply only inside their ecosystem"
    (is (= "medley/medley" (load/canonical-coord aliases :hex "medley/medley")))))

(deftest alias-table-is-well-formed
  (is (map? aliases))
  (is (every? #{:maven :hex :opam :zig} (keys aliases)))
  (doseq [[eco table] aliases]
    (is (every? string? (keys table)) (str eco))
    (is (every? string? (vals table)) (str eco))
    (is (not-any? (fn [[k v]] (= k v)) table) (str eco ": an alias maps to itself"))
    (is (empty? (set/intersection (set (keys table)) (set (vals table))))
        (str eco ": a canonical coord is itself aliased, so a single lookup would stop halfway"))))

(deftest declaration-fields
  (let [tx (load/repo-tx (repo 5 :ocaml) nil
                         {:repo/declarations [(decl "bin/dune" :opam :library ["str" nil] ["mylib" nil true])
                                              {:decl/source "broken.opam" :decl/status :unreadable
                                               :decl/ecosystem :opam :decl/kind :package}]}
                         nil ctx)
        decls (sort-by :decl/key (entities-with :decl/key tx))]
    (is (= [:library :library] (map :decl/kind decls)))
    (is (= #{true false} (set (map :decl/local? decls))))
    (is (= ["broken.opam"] (:repo/unreadable-build-files (first (entities-with :repo/id tx)))))))

;; ---------------------------------------------------------------------------
;; Dates

(deftest dates
  (is (= #inst "2021-12-01T10:00:00Z" (load/parse-instant "2021-12-01T10:00:00Z")))
  (is (= #inst "2023-12-01T09:00:00Z" (load/parse-instant "2023-12-01T10:00:00+01:00"))
      "git's %cI keeps the committer's offset")
  (testing "author-controlled garbage is nil, not an exception"
    (doseq [s ["2023-12-01T10:00:00+99:59" "2023-12-01T10:00:00+19:00"
               "10000-01-01T00:00:00+00:00" "yesterday" ""]]
      (is (nil? (load/parse-instant s)) s)))
  (is (nil? (load/parse-instant nil))))

(deftest invalid-dates-become-absent-attributes
  ;; security[9] / datomic-and-kondo[11]
  (let [r  (repo 6 :zig :repo/created-at "2023-12-01T10:00:00+99:59")
        ce {:clone/status :cloned :clone/sha "abc" :clone/commit-date "10000-01-01T00:00:00+00:00"}
        tx (load/repo-tx r ce {:repo/files []} nil ctx)
        re (first (entities-with :repo/id tx))]
    (is (not (contains? re :repo/created-at)))
    (is (not (contains? re :repo/commit-date)))
    (is (= #inst "2021-12-25T10:00:00Z" (:repo/pushed-at re)))
    (is (not-any? nil? (vals re)) "no nil values reach Datomic")
    (is (= [:repo/created-at :clone/commit-date] (load/dropped-dates r ce)))))

;; ---------------------------------------------------------------------------
;; Vars

(deftest added-minor
  ;; datomic-and-kondo[10]
  (is (= 11 (load/added-minor "1.11")))
  (is (= 9 (load/added-minor "1.9")))
  (is (= 0 (load/added-minor "1.0")))
  (is (nil? (load/added-minor nil)))
  (is (nil? (load/added-minor "2.0")) "only Clojure 1.x minors are comparable")
  (is (= [0 2 5 9 10 11 12] (sort (map load/added-minor ["1.9" "1.10" "1.2" "1.11" "1.0" "1.12" "1.5"])))))

(deftest var-entities-carry-added-minor
  (let [kondo {:kondo/files {"src/day01.clj" {:requires #{} :usages {"clojure.core/parse-long" 2
                                                                     "clojure.core/some->" 1
                                                                     "clojure.core/*fn-loader*" 1}
                                              :java {} :syntax-errors 0}}}
        tx (load/repo-tx (repo 7 :clojure) nil
                         {:repo/files [{:file/path "src/day01.clj" :file/lang :clojure :file/status :ok :file/bytes 10}]}
                         kondo ctx)
        vars (into {} (map (juxt :var/fqn identity)) (entities-with :var/fqn tx))]
    (is (= 11 (:var/added-minor (vars "clojure.core/parse-long"))))
    (is (= "1.11" (:var/added (vars "clojure.core/parse-long"))))
    (is (= 5 (:var/added-minor (vars "clojure.core/some->"))))
    (is (not (contains? (vars "clojure.core/*fn-loader*") :var/added-minor)))))

;; ---------------------------------------------------------------------------
;; Files: new extract and kondo fields

(deftest new-file-fields
  (let [extract {:repo/files [{:file/path "src/day01.clj" :file/lang :clojure :file/status :ok :file/bytes 100
                               :file/role :solution :file/template? false :file/lines 20 :file/code-lines 15
                               :file/test-lines 3 :file/doc-lines 2 :file/shared-lines 4
                               :file/comment-rows [[10 12]]
                               :puzzle/keys [[2021 1]] :puzzle/source :path}
                              {:file/path "lib/mix/tasks/day.ex" :file/lang :elixir :file/status :ok
                               :file/bytes 50 :file/role :runner}
                              {:file/path "src/link.clj" :file/lang :clojure :file/status :symlink}]}
        kondo   {:kondo/files {"src/day01.clj" {:requires #{"clojure.string"} :usages {"clojure.core/map" 1}
                                                :java {} :syntax-errors 0
                                                :analysed? true :special-forms 7 :unresolved 2}
                               "src/link.clj" {:requires #{} :usages {} :java {} :syntax-errors 0
                                               :analysed? false}}}
        tx      (load/repo-tx (repo 8 :clojure) nil extract kondo ctx)
        files   (into {} (map (juxt :file/path identity)) (entities-with :file/key tx))
        day     (files "src/day01.clj")]
    (is (= {:file/test-lines 3 :file/doc-lines 2 :file/shared-lines 4 :file/template? false
            :file/analysed? true :file/special-forms 7 :file/unresolved 2}
           (select-keys day [:file/test-lines :file/doc-lines :file/shared-lines :file/template?
                             :file/analysed? :file/special-forms :file/unresolved])))
    (is (= :runner (:file/role (files "lib/mix/tasks/day.ex"))))
    (is (= :symlink (:file/status (files "src/link.clj"))))
    (is (false? (:file/analysed? (files "src/link.clj"))))
    (is (not-any? nil? (mapcat vals (vals files))) "missing fields are absent, not nil")))

(deftest kondo-paths-missing-from-the-extract-are-skipped
  ;; A stale kondo file (datomic-and-kondo[9]) would otherwise reference a
  ;; tempid that no entity defines, and Datomic rejects the whole repo.
  (let [extract {:repo/files [{:file/path "src/a.clj" :file/lang :clojure :file/status :ok :file/bytes 1}]}
        kondo   {:kondo/files {"src/a.clj"    {:requires #{} :usages {"clojure.core/map" 1} :java {} :syntax-errors 0}
                               "src/gone.clj" {:requires #{} :usages {"clojure.core/keep" 1} :java {} :syntax-errors 0}}}
        tx      (load/repo-tx (repo 9 :clojure) nil extract kondo ctx)]
    (is (= #{"f:src/a.clj"} (set (map :usage/file (entities-with :usage/key tx)))))
    (is (= ["src/gone.clj"] (load/stale-kondo-paths extract kondo)))))

(deftest every-attribute-written-is-in-the-schema
  (let [extract {:repo/files [{:file/path "src/day01.clj" :file/lang :clojure :file/status :ok :file/bytes 100
                               :file/role :solution :file/template? false :file/lines 20 :file/code-lines 15
                               :file/code-lines-no-comment-forms 14 :file/runtime :jvm :file/module "aoc.day01"
                               :file/test-lines 3 :file/doc-lines 2 :file/shared-lines 4
                               :puzzle/keys [[2021 1]] :puzzle/source :path}]
                 :repo/build-files #{:deps-edn} :repo/ai-files #{:claude-md} :repo/editor-signals #{:calva}
                 :repo/java-version ["17" ".java-version"]
                 :repo/declarations [(decl "deps.edn" :maven :package ["medley/medley" "1.4.0"])
                                     {:decl/source "x/deps.edn" :decl/status :unreadable}]}
        kondo   {:kondo/files {"src/day01.clj" {:requires #{"clojure.string"} :usages {"clojure.core/doto" 1}
                                                :java {"java.lang.Long/parseLong" 1} :syntax-errors 0
                                                :analysed? true :special-forms 1 :unresolved 0}}}
        tx      (load/repo-tx (repo 10 :clojure) {:clone/status :cloned :clone/sha "abc" :clone/disk-kb 5
                                                  :clone/commit-date "2023-12-01T10:00:00+01:00"}
                              extract kondo ctx)
        idents  (set (map :db/ident db/schema))
        written (set (remove #{:db/id} (mapcat keys tx)))]
    (is (empty? (set/difference written idents)))))

(deftest schema-keys-deps-by-ecosystem
  (let [by-ident (into {} (map (juxt :db/ident identity)) db/schema)]
    (is (= :db.unique/identity (:db/unique (by-ident :dep/key))))
    (is (nil? (:db/unique (by-ident :dep/coord))) ":dep/coord is shared across ecosystems")
    (is (= :db.type/keyword (:db/valueType (by-ident :dep/ecosystem))))
    (is (= :db.type/long (:db/valueType (by-ident :var/added-minor))))))

;; ---------------------------------------------------------------------------
;; Loading into Datomic (in memory)

(defn- inputs-from [m]
  (fn [{:repo/keys [id]}]
    (let [v (get m id)]
      (if (instance? Throwable v) (throw v) v))))

(deftest failed-repos-are-not-counted-as-loaded
  ;; datomic-and-kondo[1] and [11], security[9]
  (let [conn  (db/connect (mem-client) "t")
        run   (load/start-run! conn {:note "test"})
        repos [(repo 100 :clojure)
               (repo 101 :clojure)
               (repo 102 :zig :repo/created-at "2023-12-01T10:00:00+99:59")
               (repo 103 :clojure)]
        res   (load/load-repos! conn run repos
                                (inputs-from
                                 {100 {:extract {:repo/files [{:file/path "a.clj" :file/lang :clojure :file/status :ok :file/bytes 1}]}}
                                  101 (ex-info "EOF while reading" {})
                                  102 {:clone-entry {:clone/status :cloned :clone/commit-date "2023-12-01T10:00:00+99:59"}}
                                  ;; a type error Datomic rejects at transact time
                                  103 {:extract {:repo/files [{:file/path "b.clj" :file/lang :clojure :file/status :ok
                                                               :file/bytes "lots"}]}}})
                                ctx)
        db    (d/db conn)]
    (is (= 2 (:loaded res)))
    (is (= [101 103] (mapv :repo/id (:failed res))))
    (is (every? string? (map :error (:failed res))))
    (is (= (:failed res) (edn/read-string (pr-str (:failed res)))) "failures are plain EDN")
    (is (= [[102 [:repo/created-at :clone/commit-date]]] (:dropped-dates res)))
    (testing "the repo with bad dates is loaded, without those attributes"
      (is (= #{[102]} (set (q1 db '[:find ?id :where [?r :repo/id ?id] [?r :repo/lang :zig]]))))
      (is (empty? (q1 db '[:find ?r :where [?r :repo/id 102] [?r :repo/created-at]]))))
    (is (= #{[100] [102]} (set (q1 db '[:find ?id :where [?r :repo/id ?id]]))))
    (testing "the run records what it loaded"
      (load/finish-run! conn run res)
      (is (= #{[2 2]} (set (q1 (d/db conn) '[:find ?l ?f :in $ ?run :where [?e :run/id ?run]
                                               [?e :run/loaded ?l] [?e :run/failed ?f]]
                                 run)))))))

(deftest exit-code
  (is (= 0 (load/exit-code {:loaded 3 :failed []})))
  (is (= 1 (load/exit-code {:loaded 3 :failed [{:repo/id 1 :error "x"}]}))))

(deftest failures-file
  (let [f (str (fs/create-temp-file {:suffix ".edn"}))]
    (load/write-failures! f {:run "r1" :db "research" :failed [{:repo/id 1 :repo/lang :clojure :error "boom"}]})
    (is (= {:run "r1" :db "research" :failed [{:repo/id 1 :repo/lang :clojure :error "boom"}]}
           (edn/read-string (slurp f))))
    (fs/delete f)))

(deftest deps-keep-their-ecosystem-across-repos
  ;; deps-parsing[0] replayed in load order: a later Zig repo with a deps.edn
  ;; must not relabel org.clojure/clojure.
  (let [conn (db/connect (mem-client) "t")
        run  (load/start-run! conn {:note "test"})
        res  (load/load-repos! conn run [(repo 200 :clojure) (repo 201 :elixir) (repo 202 :ocaml) (repo 203 :zig)]
                               (inputs-from
                                {200 {:extract {:repo/declarations [(decl "deps.edn" :maven :package ["org.clojure/clojure" "1.11.1"])]}}
                                 201 {:extract {:repo/declarations [(decl "mix.exs" :hex :package ["aoc" "~> 0.1"])
                                                                    (decl "clj/project.clj" :maven :package ["org.clojure/clojure" "1.10.0"])]}}
                                 202 {:extract {:repo/declarations [(decl "aoc.opam" :opam :package ["aoc" nil])]}}
                                 203 {:extract {:repo/declarations [(decl "deps.edn" :maven :package ["org.clojure/clojure" "1.12.0"])]}}})
                               ctx)
        db   (d/db conn)]
    (is (= [] (:failed res)))
    (is (= #{["maven:org.clojure/clojure" :maven] ["hex:aoc" :hex] ["opam:aoc" :opam]}
           (set (q1 db '[:find ?k ?e :where [?d :dep/key ?k] [?d :dep/ecosystem ?e]]))))
    (is (= #{[3]} (set (q1 db '[:find (count-distinct ?r) :where [?d :dep/key "maven:org.clojure/clojure"]
                                [?x :decl/dep ?d] [?x :decl/repo ?r]]))))))

;; ---------------------------------------------------------------------------
;; --fresh / --append

(deftest load-plan
  ;; datomic-and-kondo[0], deps-parsing[4]
  (is (= {:action :load} (load/load-plan {:populated? false})))
  (is (:refuse (load/load-plan {:populated? true :db-name "research"})))
  (is (re-find #"--fresh" (:refuse (load/load-plan {:populated? true :db-name "research"}))))
  (is (= {:action :recreate} (load/load-plan {:fresh? true :populated? true})))
  (is (= {:action :load} (load/load-plan {:append? true :populated? true})))
  (is (:refuse (load/load-plan {:fresh? true :append? true})))
  (testing "an old-schema database (unique :dep/coord) is refused even with --append"
    (is (:refuse (load/load-plan {:append? true :populated? true :legacy-schema? true})))
    (is (= {:action :recreate} (load/load-plan {:fresh? true :legacy-schema? true})))))

(deftest parse-args
  (is (= {:db-name "research" :fresh? false :append? false :note "load" :limit nil :ids nil}
         (load/parse-args [])))
  (is (= {:db-name "x" :fresh? true :append? false :note "full-run" :limit 5 :ids #{1 22}}
         (load/parse-args ["--db" "x" "--fresh" "--note" "full-run" "--limit" "5" "--ids" "1,22"]))))

(deftest prepare-db
  (let [client (mem-client)
        load1  (fn [opts]
                 (let [{:keys [conn refuse]} (load/prepare-db! client "research" opts)]
                   (or refuse
                       (let [run (load/start-run! conn {:note "t"})]
                         (load/load-repos! conn run [(repo 300 :clojure)] (constantly {}) ctx)
                         conn))))]
    (is (some? (d/db (load1 {}))) "an empty database loads by default")
    (let [refusal (load1 {})]
      (is (string? refusal) "a populated database is refused by default")
      (is (re-find #"--append" refusal)))
    (let [conn (load1 {:append? true})]
      (is (= 2 (count (q1 (d/db conn) '[:find ?r :where [?r :run/id]])))))
    (let [conn (load1 {:fresh? true})]
      (is (= 1 (count (q1 (d/db conn) '[:find ?r :where [?r :run/id]]))) "--fresh starts from an empty database")
      (is (= #{[300]} (set (q1 (d/db conn) '[:find ?id :where [?r :repo/id ?id]])))))))

(deftest legacy-schema-detection
  (let [client (mem-client)
        conn   (db/open client "old")]
    (d/transact conn {:tx-data [{:db/ident :dep/coord :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}]})
    (is (db/legacy-dep-schema? (d/db conn)))
    (is (string? (:refuse (load/prepare-db! client "old" {:append? true}))))
    (is (not (db/legacy-dep-schema? (d/db (db/connect client "new")))))))
