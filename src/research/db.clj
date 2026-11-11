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
   (attr :run/mode :keyword {:doc ":new (empty database), :fresh (deleted and recreated first) or :append"})
   (attr :run/loaded :long {:doc "Repos whose transaction succeeded."})
   (attr :run/failed :long {:doc "Repos that were not loaded; listed in load-failures.edn."})
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
   (attr :file/role :keyword {:doc ":solution, :test, :util, :config or :runner (Elixir mix tasks)"})
   (attr :file/promoted-from :keyword {:doc "Original role (:runner or :test) of a file promoted to :solution because its puzzle had no other solution file in the repo."})
   (attr :file/fingerprint :string {:doc "SHA-1 of the content with digits normalised; a fingerprint shared by many repos marks generated boilerplate."})
   (attr :file/template? :boolean {:doc "Same content (digits normalised) as files for other puzzles in the repo: a generated or copied-in template."})
   (attr :file/status :keyword {:doc ":ok, :too-large or :symlink. Only :ok files have line counts."})
   (attr :file/bytes :long)
   (attr :file/runtime :keyword {:doc ":jvm, :bb, :nbb or :cljs"})
   (attr :file/module :string)
   (attr :file/lines :long)
   (attr :file/code-lines :long)
   (attr :file/code-lines-no-comment-forms :long)
   ;; Copied from the extract's fields of the same name; research.inventory
   ;; defines them. Absent when the extract predates them.
   (attr :file/test-lines :long)
   (attr :file/doc-lines :long)
   (attr :file/shared-lines :long)
   (attr :file/puzzles :ref {:many true})
   (attr :file/puzzle-source :keyword {:doc ":path, :ns, :repo-name or :created-at"})
   (attr :file/requires :ref {:many true})
   (attr :file/syntax-errors :long)
   ;; The next three are copied from the kondo stage's per-file :analysed?,
   ;; :special-forms and :unresolved; research.kondo defines them. Absent when
   ;; the kondo output predates them.
   (attr :file/analysed? :boolean {:doc "False when the kondo stage reports the file's analysis as lost, so its usages are missing rather than zero."})
   (attr :file/special-forms :long {:doc "Special-form reference count from the kondo stage."})
   (attr :file/unresolved :long {:doc "Unresolved reference count from the kondo stage."})

   ;; namespaces and vars (questions 2, 5-7, 9, 10)
   (attr :ns/name :string {:unique true})
   (attr :var/fqn :string {:unique true})
   (attr :var/added :string {:doc "Clojure version that added it, from the var's own metadata. A string, so it sorts \"1.9\" > \"1.11\": order by :var/added-minor."})
   (attr :var/added-minor :long {:doc "Minor version of :var/added for Clojure 1.x (\"1.11\" -> 11), for ordering and max/min."})
   (attr :var/macro? :boolean)
   (attr :var/java? :boolean {:doc "A Java class or member, e.g. java.lang.Long/parseLong."})

   ;; usages: (file, var) -> count
   (attr :usage/key :string {:unique true})
   (attr :usage/file :ref)
   (attr :usage/var :ref)
   (attr :usage/count :long)

   ;; dependencies (questions 1, 4, 18)
   (attr :dep/key :string {:unique true :doc "\"<ecosystem>:<coord>\", e.g. \"maven:org.clojure/clojure\". Same-named packages in different ecosystems are different deps."})
   (attr :dep/coord :string {:doc "Coordinate as declared: \"org.clojure/clojure\", \"jason\". Not unique across ecosystems."})
   (attr :dep/ecosystem :keyword {:doc ":maven, :hex, :opam or :zig, from the build file type (not the repo's language)."})
   (attr :dep/canonical-coord :string {:doc "The coordinate the library goes by now (data/dep-aliases.edn), or :dep/coord when it has no alias. Group by this to count a renamed library once."})
   (attr :decl/key :string {:unique true})
   (attr :decl/repo :ref)
   (attr :decl/dep :ref)
   (attr :decl/version :string)
   (attr :decl/source :string {:doc "Build file it came from, e.g. deps.edn"})
   (attr :decl/scope :keyword {:doc ":main, an alias, or a Leiningen profile"})
   (attr :decl/kind :keyword {:doc ":package (a declared package: deps.edn, mix.exs, opam ...) or :library (a dune file's (libraries ...))"})
   (attr :decl/local? :boolean {:doc "True for a local or in-repo dependency rather than a published one; research.deps decides."})

   ;; tags (questions 8, 17): :rule tags written by rules, :manual by Jordan
   (attr :tag/key :string {:unique true})
   (attr :tag/puzzle :ref)
   (attr :tag/concept :keyword)
   (attr :tag/source :keyword)
   (attr :tag/note :string)])

(def system "aoc-research")

(defn client
  "A Datomic Local client. By default on disk in the cache; pass
  {:storage-dir :mem :system \"x\"} for an in-memory one (tests)."
  ([] (client {}))
  ([opts]
   (d/client (merge {:server-type :datomic-local
                     :storage-dir (cache/path "datomic")
                     :system      system}
                    opts))))

(defn open
  "Connect to db-name, creating it if needed. Does not transact the schema,
  so a database can be inspected without being changed."
  [client db-name]
  (d/create-database client {:db-name db-name})
  (d/connect client {:db-name db-name}))

(defn install-schema! [conn] (d/transact conn {:tx-data schema}))

(defn connect
  "Connect to db-name (creating it if needed) and transact the schema."
  ([] (connect "research"))
  ([db-name] (connect (client) db-name))
  ([client db-name]
   (let [conn (open client db-name)]
     (install-schema! conn)
     conn)))

(defn recreate!
  "Delete db-name and create it empty. Datomic Local's delete-database
  returns true whether or not the database existed."
  [client db-name]
  (d/delete-database client {:db-name db-name})
  (d/create-database client {:db-name db-name}))

(defn populated?
  "True when db holds any run or repo, i.e. something was loaded before.
  Safe on a database without the schema."
  [db]
  (boolean (seq (d/q {:query '[:find ?e :in $ [?ident ...] :where [?a :db/ident ?ident] [?e ?a]]
                      :args  [db [:run/id :repo/id]]
                      :limit 1}))))

(defn legacy-dep-schema?
  "True when :dep/coord is a unique identity, as it was before deps were keyed
  by ecosystem. Transacting the current schema does not drop that uniqueness,
  so loading would merge same-named packages from different ecosystems."
  [db]
  (boolean (seq (d/q '[:find ?a :where [?a :db/ident :dep/coord] [?a :db/unique _]] db))))
