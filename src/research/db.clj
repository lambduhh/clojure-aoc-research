(ns research.db
  "Datomic Local, on disk in the cache. The schema follows the research
  questions: every attribute here is needed by at least one query."
  (:require [datomic.client.api :as d]
            [research.cache :as cache]))

(defn- attr
  ([ident type] (attr ident type {}))
  ([ident type opts]
   (merge {:db/ident ident
           :db/valueType (keyword "db.type" (name type))
           :db/cardinality (if (:many opts) :db.cardinality/many :db.cardinality/one)}
          (when (:unique opts) {:db/unique :db.unique/identity})
          (when (:doc opts) {:db/doc (:doc opts)}))))

(def schema
  [;; runs: every transaction a run makes points at its run entity
   (attr :run/id :string {:unique true})
   (attr :run/started :instant)
   (attr :run/dataset-commit :string)
   (attr :run/note :string)
   (attr :tx/run :ref {:doc "On transaction entities: the run that made this transaction."})

   ;; repos (questions 3, 15, 18-23, 25)
   (attr :repo/id :long {:unique true :doc "GitHub's numeric repo id."})
   (attr :repo/full-name :string)
   (attr :repo/lang :keyword {:doc "Primary language, from the dataset it came from."})
   (attr :repo/created-at :instant)
   (attr :repo/pushed-at :instant)
   (attr :repo/size-kb :long)
   (attr :repo/stars :long)
   (attr :repo/archived? :boolean)
   (attr :repo/clone-status :keyword)
   (attr :repo/sha :string {:doc "Commit analysed, for reproducibility."})
   (attr :repo/commit-date :instant)
   (attr :repo/disk-kb :long)
   (attr :repo/build-files :keyword {:many true})
   (attr :repo/ai-files :keyword {:many true})
   (attr :repo/editor-signals :keyword {:many true})
   (attr :repo/java-version :string)
   (attr :repo/java-version-source :string)
   (attr :repo/unreadable-build-files :string {:many true})

   ;; puzzles
   (attr :puzzle/key :string {:unique true :doc "\"2021-07\""})
   (attr :puzzle/year :long)
   (attr :puzzle/day :long)

   ;; files (questions 2, 11-14, 23)
   (attr :file/key :string {:unique true :doc "\"<lang>:<repo id>:<path>\""})
   (attr :file/repo :ref)
   (attr :file/path :string)
   (attr :file/lang :keyword)
   (attr :file/role :keyword {:doc ":solution, :test or :util"})
   (attr :file/status :keyword)
   (attr :file/bytes :long)
   (attr :file/runtime :keyword {:doc ":jvm, :bb, :nbb or :cljs"})
   (attr :file/module :string)
   (attr :file/lines :long)
   (attr :file/code-lines :long)
   (attr :file/code-lines-no-comment-forms :long)
   (attr :file/puzzles :ref {:many true})
   (attr :file/puzzle-source :keyword {:doc ":path, :ns, :repo-name or :created-at"})
   (attr :file/requires :ref {:many true})
   (attr :file/syntax-errors :long)

   ;; namespaces and vars (questions 2, 5-7, 9, 10)
   (attr :ns/name :string {:unique true})
   (attr :var/fqn :string {:unique true})
   (attr :var/added :string {:doc "Clojure version that added it, from the var's own metadata."})
   (attr :var/macro? :boolean)
   (attr :var/java? :boolean {:doc "A Java class or member, e.g. java.lang.Long/parseLong."})

   ;; usages: (file, var) -> count
   (attr :usage/key :string {:unique true})
   (attr :usage/file :ref)
   (attr :usage/var :ref)
   (attr :usage/count :long)

   ;; dependencies (questions 1, 4, 18)
   (attr :dep/coord :string {:unique true})
   (attr :dep/ecosystem :keyword {:doc ":maven, :hex, :opam or :zig"})
   (attr :decl/key :string {:unique true})
   (attr :decl/repo :ref)
   (attr :decl/dep :ref)
   (attr :decl/version :string)
   (attr :decl/source :string {:doc "Build file it came from, e.g. deps.edn"})
   (attr :decl/scope :keyword {:doc ":main, an alias, or a Leiningen profile"})

   ;; tags (questions 8, 17): :rule tags written by rules, :manual by Jordan
   (attr :tag/key :string {:unique true})
   (attr :tag/puzzle :ref)
   (attr :tag/concept :keyword)
   (attr :tag/source :keyword)
   (attr :tag/note :string)])

(def system "aoc-research")

(defn client []
  (d/client {:server-type :datomic-local
             :storage-dir (cache/path "datomic")
             :system      system}))

(defn connect
  ([] (connect "research"))
  ([db-name]
   (let [c (client)]
     (d/create-database c {:db-name db-name})
     (let [conn (d/connect c {:db-name db-name})]
       (d/transact conn {:tx-data schema})
       conn))))
