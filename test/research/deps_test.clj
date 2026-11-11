(ns research.deps-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [research.deps :as d]))

(defn- dep
  "Expected dependency entry; local? defaults to false."
  ([coord version scope] (dep coord version scope false))
  ([coord version scope local?]
   {:dep/coord coord :decl/version version :decl/scope scope :decl/local? local?}))

(defn- round-trips? [x] (= x (edn/read-string (pr-str x))))

(defn- on-small-stack
  "Runs f on a thread with a 256 KB stack (extract workers are pool threads,
  whose stacks are smaller than the main thread's). Returns f's value, or
  [:threw <class>]."
  [f]
  (let [p (promise)
        t (Thread. nil
                   (fn [] (deliver p (try (f) (catch Throwable t [:threw (class t)]))))
                   "small-stack" (* 256 1024))]
    (.start t)
    (.join t)
    @p))

(defn- elapsed-ms [f]
  (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6)))

(def ^:private mb (* 1024 1024))
(defn- mb-of [s] (let [s (apply str (repeat (inc (quot mb (count s))) s))] (subs s 0 mb)))

;; ---------------------------------------------------------------------------
;; deps.edn

(deftest deps-edn
  (is (= [(dep "org.clojure/clojure" "1.11.1" :main)
          (dep "medley/medley" "1.4.0" :main)
          (dep "io.github.cognitect-labs/test-runner" "v0.5.1" :test)]
         (d/deps-edn-deps "{:deps {org.clojure/clojure {:mvn/version \"1.11.1\"} medley {:mvn/version \"1.4.0\"}}
                              :aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}}}}")))
  (is (= :research.deps/unreadable (d/deps-edn-deps "{:deps"))))

(deftest deps-edn-alias-deps
  ;; deps-parsing [3]: alias :deps is a synonym for :replace-deps
  (is (= [(dep "io.github.clojure/tools.build" "v0.10.5" :build)]
         (d/deps-edn-deps "{:aliases {:build {:deps {io.github.clojure/tools.build {:git/tag \"v0.10.5\" :git/sha \"2a21b7a\"}}
                                                 :ns-default build}}}"))))

(deftest deps-edn-local-root
  ;; deps-parsing [7]: the prefix goes in front, and local deps are flagged
  (is (= [(dep "util/util" "local:../util" :main true)
          (dep "medley/medley" "1.4.0" :main false)]
         (d/deps-edn-deps "{:deps {util/util {:local/root \"../util\"} medley/medley {:mvn/version \"1.4.0\"}}}"))))

(deftest deps-edn-unqualified-git-keys
  ;; deps-parsing [14]: pre-2021 tools.deps used :sha and :tag
  (is (= [(dep "foo/bar" "abcdef1" :main)
          (dep "baz/qux" "v1.2" :main)]
         (d/deps-edn-deps "{:deps {foo/bar {:git/url \"https://x\" :sha \"abcdef1234567\"}
                                   baz/qux {:git/url \"https://y\" :tag \"v1.2\" :sha \"0123456789\"}}}"))))

(deftest deps-edn-non-string-values
  ;; security [3]: a non-string :git/sha must not throw
  (doseq [sha ["123" ":kw" "[1 2]" "#my/tag \"x\"" "nil"]]
    (is (= [(dep "a/b" nil :main)] (d/deps-edn-deps (str "{:deps {a/b {:git/sha " sha "}}}")))
        (str ":git/sha " sha)))
  ;; security [1]: a tagged literal is not carried into the output
  (is (= [(dep "a/b" nil :main)] (d/deps-edn-deps "{:deps {a/b {:mvn/version #my/tag \"1\"}}}")))
  (is (= [(dep "a/b" nil :main true)] (d/deps-edn-deps "{:deps {a/b {:local/root 5}}}"))
      "a non-string :local/root is not a version, but the key still marks the dep local")
  (let [out (d/deps-edn-deps "{:aliases {#my/tag x {:extra-deps {a/b {:mvn/version \"1\"}}}
                                         \"my alias\" {:extra-deps {c/d {:mvn/version \"2\"}}}
                                         :ok/alias {:extra-deps {e/f {:mvn/version \"3\"}}}}}")]
    (is (round-trips? out))
    (is (every? keyword? (map :decl/scope out)))
    (is (some #{:ok/alias} (map :decl/scope out)) "ordinary alias keywords are kept as they are")))

;; ---------------------------------------------------------------------------
;; project.clj, shadow-cljs.edn, build.boot

(deftest project-clj
  (is (= [(dep "org.clojure/clojure" "1.10.1" :main)
          (dep "instaparse/instaparse" "1.4.10" :main)
          (dep "criterium/criterium" "0.4.6" :profile-dev)]
         (d/project-clj-deps "(defproject aoc \"0.1.0\" :description \"x\"
                                  :dependencies [[org.clojure/clojure \"1.10.1\"] [instaparse \"1.4.10\"]]
                                  :profiles {:dev {:dependencies [[criterium \"0.4.6\"]]}})")))
  (testing "never evaluated: read-eval and unquote are not run"
    (let [marker (str (fs/create-temp-file))]
      (fs/delete marker)
      (d/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a \"1\"]] :x #=(spit \"" marker "\" \"pwned\"))"))
      (d/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a ~(spit \"" marker "\" \"pwned\")]])"))
      (is (not (fs/exists? marker))))))

(deftest project-clj-plugins
  ;; deps-parsing [11]
  (is (= [(dep "org.clojure/clojure" "1.11.1" :main)
          (dep "lein-cljfmt/lein-cljfmt" "0.9.0" :plugin)
          (dep "criterium/criterium" "0.4.6" :profile-dev)
          (dep "cider/cider-nrepl" "0.28.5" :plugin)]
         (d/project-clj-deps "(defproject aoc \"0.1.0\"
                                 :dependencies [[org.clojure/clojure \"1.11.1\"]]
                                 :plugins [[lein-cljfmt \"0.9.0\"]]
                                 :profiles {:dev {:dependencies [[criterium \"0.4.6\"]]
                                                  :plugins [[cider/cider-nrepl \"0.28.5\"]]}})"))))

(deftest project-clj-profile-keys-round-trip
  ;; security [1]: profile names become keywords that read back as EDN
  (let [out (d/project-clj-deps "(defproject aoc \"1\"
                                   :profiles {\"my dev\" {:dependencies [[cider/cider-nrepl \"0.1\"]]}
                                              \"x} {:dep/coord \\\"evil/injected\\\"\" {:dependencies [[a \"1\"]]}
                                              7 {:dependencies [[b \"2\"]]}})")]
    (is (= 3 (count out)))
    (is (round-trips? out))
    (is (every? keyword? (map :decl/scope out)))
    (is (not-any? #(str/includes? (pr-str %) "evil/injected") (map :decl/scope out)))))

(deftest shadow-cljs
  ;; deps-parsing [12]
  (is (= [(dep "reagent/reagent" "1.2.0" :main)
          (dep "binaryage/devtools" "1.0.7" :main)]
         (d/shadow-cljs-deps "{:source-paths [\"src\"]
                               :dependencies [[reagent \"1.2.0\"] [binaryage/devtools \"1.0.7\"]]
                               :builds {:app {:target :browser :closure-defines {x #shadow/env \"X\"}}}}")))
  (is (= [] (d/shadow-cljs-deps "{:deps true :builds {}}")))
  (is (= :research.deps/unreadable (d/shadow-cljs-deps "{:dependencies"))))

(deftest boot
  ;; deps-parsing [12]
  (is (= [(dep "org.clojure/clojure" "1.9.0" :main)
          (dep "adzerk/boot-test" "1.2.0" :main)
          (dep "criterium/criterium" "0.4.4" :main)]
         (d/boot-deps "(set-env! :source-paths #{\"src\"}
                                 :dependencies '[[org.clojure/clojure \"1.9.0\"]
                                                 [adzerk/boot-test \"1.2.0\" :scope \"test\" :exclusions [org.clojure/clojure]]])
                       (set-env! :dependencies #(conj % '[criterium \"0.4.4\"]))
                       (require '[adzerk.boot-test :refer :all])
                       (deftask build [] (comp (pom) (jar)))")))
  (is (= [] (d/boot-deps "(deftask build [] identity)")))
  (testing "never evaluated"
    (let [marker (str (fs/create-temp-file))]
      (fs/delete marker)
      (d/boot-deps (str "(spit \"" marker "\" \"pwned\") (set-env! :dependencies '[[a \"1\"]] :x #=(spit \"" marker "\" \"x\"))"))
      (d/boot-deps (str "(set-env! :dependencies (do (spit \"" marker "\" \"pwned\") '[[a \"1\"]]))"))
      (is (not (fs/exists? marker))))))

;; ---------------------------------------------------------------------------
;; mix.exs

(deftest mix-comments-are-ignored
  (is (= [(dep "phoenix_live_view" "~> 1.0.0-rc" :main)]
         (d/mix-exs-deps "  # TODO bump on release to {:phoenix_live_view, \"~> 1.0.0\"},\n  {:phoenix_live_view, \"~> 1.0.0-rc\", override: true}, # trailing\n"))))

(deftest mix-only-scopes
  ;; deps-parsing [9]
  (is (= [(dep "jason" "~> 1.4" :main)
          (dep "credo" "~> 1.7" :dev)
          (dep "mix_test_watch" "~> 1.0" :dev)
          (dep "excoveralls" "~> 0.18" :test)
          (dep "benchee" "~> 1.0" :dev)
          (dep "inch_ex" nil :docs)]
         (d/mix-exs-deps "defp deps do
                            [
                              {:jason, \"~> 1.4\"},
                              {:credo, \"~> 1.7\", only: [:dev, :test], runtime: false},
                              {:mix_test_watch, \"~> 1.0\", only: :dev, runtime: false},
                              {:excoveralls, \"~> 0.18\", only: :test},
                              {:benchee, \"~> 1.0\", only: [:test, :dev]},
                              {:inch_ex, only: :docs}
                            ]
                          end"))))

(deftest mix-keyword-only-tuples
  ;; deps-parsing [10] and [7]
  (is (= [(dep "comb" "master" :main)
          (dep "libgraph" nil :main)
          (dep "typed_struct_uberbrodt" "~> 0.1.4" :main)
          (dep "common" "local:../../common" :main true)
          (dep "util" nil :main true)
          (dep "exsync" "local:~/dev/forks/exsync" :dev true)]
         (d/mix-exs-deps "defp deps do
                            [{:comb, git: \"https://github.com/tallakt/comb.git\", tag: \"master\"},
                             {:libgraph, github: \"bitwalker/libgraph\"},
                             {:typed_struct, \"~> 0.1.4\", hex: :typed_struct_uberbrodt},
                             {:common, path: \"../../common\"},
                             {:util, in_umbrella: true},
                             {:exsync, path: \"~/dev/forks/exsync\", only: :dev}]
                          end")))
  (testing "tuples that are not dependencies"
    (is (= [] (d/mix-exs-deps "def project, do: [app: :aoc, version: \"0.1.0\", deps: deps()]
                               def application, do: [extra_applications: [:logger], mod: {Aoc.App, []}]
                               defp aliases, do: [test: [\"format\", \"test\"]]
                               defp deps, do: []")))))

(deftest mix-strings-and-sigils
  (testing "brackets and quotes inside strings, interpolation, sigils and char literals don't desync"
    (is (= [(dep "a" "~> 1.0" :main) (dep "b" "~> 2.0" :test)]
           (d/mix-exs-deps "@url \"https://x/#{System.get_env(\"U\")}/{[(\"
                            @re ~r/[{(/
                            @c ?{
                            @h \"\"\"
                            {:fake, \"1\"} } ] )
                            \"\"\"
                            defp deps, do: [{:a, \"~> 1.0\"}, {:b, \"~> 2.0\", only: :test}]")))))

(deftest other-build-files
  (is (= [(dep "libgraph" "~> 0.16" :main)]
         (d/mix-exs-deps "defp deps do\n  [{:libgraph, \"~> 0.16\"}]\nend")))
  (is (= ["ziglyph" "clap"] (map :dep/coord (d/zon-deps ".{ .name = \"aoc\", .dependencies = .{ .ziglyph = .{ .url = \"x\" }, .clap = .{ .url = \"y\" } } }"))))
  (is (= ["dune" "containers"] (map :dep/coord (d/opam-deps "depends: [\n  \"dune\" {>= \"3.0\"}\n  \"containers\"\n]")))))

;; ---------------------------------------------------------------------------
;; build.zig.zon

(def ^:private zig-init-zon
  ;; The template `zig init` writes (0.13), with no real dependencies.
  ".{
    // This is the default name used by packages depending on this one.
    .name = \"4_Zig\",
    .version = \"0.0.0\",
    //.minimum_zig_version = \"0.11.0\",
    .dependencies = .{
        // See `zig fetch --save <url>` for a command-line interface for adding dependencies.
        //.example = .{
        //    .url = \"https://example.com/foo.tar.gz\",
        //    .hash = \"...\",
        //    .path = \"foo\",
        //    .lazy = false,
        //},
    },
    .paths = .{
        \"build.zig\",
        \"build.zig.zon\",
        \"src\",
        // For example...
        //\"LICENSE\",
    },
}")

(deftest zon-template-has-no-deps
  ;; deps-parsing [1]
  (is (= [] (d/zon-deps zig-init-zon))))

(deftest zon-deps-are-bounded-by-braces
  ;; deps-parsing [1] and [7]
  (is (= [(dep "raylib-zig" nil :main)
          (dep "utils" "local:../utils" :main true)
          (dep "mecha" nil :main)]
         (d/zon-deps ".{
                        .name = .aoc,
                        .fingerprint = 0x1234,
                        .dependencies = .{
                            .@\"raylib-zig\" = .{ .url = \"https://github.com/x//y/archive/z.tar.gz\", .hash = \"1220ab\" },
                            // .commented = .{ .url = \"q\" },
                            .utils = .{ .path = \"../utils\" },
                            .mecha = .{ .url = \"git+https://github.com/Hejsil/mecha#abc\", .hash = \"12\", .lazy = true },
                        },
                        .paths = .{ \"\" },
                      }"))))

;; ---------------------------------------------------------------------------
;; dune-project, dune, *.opam

(deftest dune-project-template
  ;; deps-parsing [2]: the `dune init project` template
  (is (= [(dep "ocaml" nil :main) (dep "dune" nil :main)]
         (d/dune-project-deps "(lang dune 3.16)
                               (name aoc)
                               (generate_opam_files true)
                               (source (github username/reponame))
                               (authors \"Author Name <author@example.com>\")
                               (package
                                (name aoc)
                                (synopsis \"A short synopsis\")
                                (description \"A longer description\")
                                (depends ocaml dune)
                                (tags
                                 (topics \"to describe\" your project)))
                               ; See the complete stanza docs at https://dune.readthedocs.io/en/stable/reference/dune-project/index.html"))))

(deftest dune-project-constraints-and-scopes
  ;; deps-parsing [2] and [5]
  (is (= [(dep "ocaml" nil :main)
          (dep "core" nil :main)
          (dep "angstrom" nil :main)
          (dep "alcotest" nil :test)
          (dep "odoc" nil :doc)
          (dep "fmt" nil :main)
          (dep "ppx_inline_test" nil :build)]
         (d/dune-project-deps "(package (name one)
                                 (depends (ocaml (>= 4.14))
                                          (core (and (>= v0.16) (< v0.17)))
                                          angstrom
                                          (alcotest :with-test)
                                          (odoc :with-doc)))
                               #| a block comment (package (name x) (depends hidden)) |#
                               (package (name two) #;(depends skipped)
                                 (depends fmt (ppx_inline_test (or :build :with-test))))")))
  (testing "the real betterocaml dune-project"
    (is (= [(dep "ocaml" nil :main) (dep "dune" nil :build)
            (dep "alcotest" nil :test) (dep "ppx_inline_alcotest" nil :test)]
           (d/dune-project-deps "(lang dune 3.10)\n(name aoc)\n(package\n  (name aoc)\n  (synopsis \"A short synopsis\")\n  (depends\n    (ocaml\n      (>= 5.1.0))\n    (dune\n      (and\n        :build\n        (>= 3.11.1)))\n    (alcotest\n      (and\n        :with-test\n        (>= 1.7.0)))\n    (ppx_inline_alcotest\n      (and\n        :with-test\n        (>= 1.0.0)))))")))))

(deftest dune-libraries
  ;; deps-parsing [5]
  (is (= [(dep "core" nil :main) (dep "str" nil :main) (dep "angstrom.unix" nil :main)
          (dep "moonpool" nil :main)
          (dep "base" nil :main)
          (dep "alcotest" nil :test) (dep "aoc" nil :test)]
         (d/dune-deps "(library (name aoc) (libraries core str angstrom.unix (re_export moonpool)
                                                    (select foo.ml from (unix -> foo.unix.ml) (-> foo.dummy.ml))))
                       ; (executable (libraries commented))
                       (executables (names day01 day02) (libraries base) (preprocess (pps ppx_jane)))
                       (rule (targets x) (action (run gen)))
                       (test (name test_aoc) (libraries alcotest aoc))")))
  (is (= [(dep "core" nil :main)]
         (d/dune-deps "(subdir bin (executable (name main) (libraries core)))"))))

(deftest opam-filters
  ;; deps-parsing [5]: opam filter flags become scopes
  (is (= [(dep "ocaml" nil :main)
          (dep "dune" nil :build)
          (dep "alcotest" nil :test)
          (dep "ocaml-lsp-server" nil :dev)
          (dep "odoc" nil :doc)
          (dep "notest" nil :main)
          (dep "lwt" nil :test)
          (dep "async" nil :test)]
         (d/opam-deps "opam-version: \"2.0\"
                       # depends: [ \"commented\" ]
                       (* depends: [ \"block-commented\" ] *)
                       synopsis: \"\"\"depends: [ \"in-a-string\" ]\"\"\"
                       depends: [
                         \"ocaml\" {>= \"5.2.0\"}
                         \"dune\" {>= \"3.11\" & build & >= \"3.16.0\"}
                         \"alcotest\" {with-test & >= \"1.8.0\"}
                         \"ocaml-lsp-server\" {with-dev-setup & >= \"1.19.0\"}
                         \"odoc\" {with-doc}
                         \"notest\" {!with-test}
                         (\"lwt\" {>= \"5\"} | \"async\") {with-test}
                       ]
                       depopts: [ \"optional\" ]
                       build: [ [\"dune\" \"build\" \"-p\" name] ]"))))

;; ---------------------------------------------------------------------------
;; Security: bounded work and stack use on crafted input

(deftest deep-nesting-is-unreadable-not-a-crash
  ;; security [2]: no StackOverflowError escapes, even on a small stack
  (let [deep (fn [open] (apply str (repeat 20000 open)))]
    (doseq [[label f] [["deps.edn" #(d/deps-edn-deps (str "{:deps " (deep "[") "}"))]
                       ["project.clj" #(d/project-clj-deps (str "(defproject a \"1\" :dependencies " (deep "[") ")"))]
                       ["shadow-cljs.edn" #(d/shadow-cljs-deps (str "{:dependencies " (deep "[") "}"))]
                       ["build.boot" #(d/boot-deps (str "(set-env! :dependencies " (deep "[") ")"))]
                       ["dune-project" #(d/dune-project-deps (deep "("))]
                       ["dune" #(d/dune-deps (deep "("))]
                       ["opam" #(d/opam-deps (str "depends: " (deep "[")))]
                       ["mix.exs" #(d/mix-exs-deps (deep "{"))]
                       ["zon" #(d/zon-deps (deep ".{"))]]]
      (is (= :research.deps/unreadable (on-small-stack f)) label))))

(deftest linear-time-on-one-megabyte
  ;; security [4]: the old lazy regexes took minutes on these
  (doseq [[label f s] [["opam unclosed depends" d/opam-deps (mb-of "depends:[ ")]
                       ["opam unclosed string" d/opam-deps (str "depends: [ \"" (mb-of "a\\\""))]
                       ["opam unclosed comment" d/opam-deps (str "(* " (mb-of "depends: [ \"x\" "))]
                       ["opam flat" d/opam-deps (str "depends: [ " (mb-of "\"a\" {with-test} ") "]")]
                       ["dune-project depends" d/dune-project-deps (mb-of "(depends ")]
                       ["dune-project flat" d/dune-project-deps (mb-of "(package (depends a (b :with-test))) ")]
                       ["dune-project unclosed string" d/dune-project-deps (str "(package (depends \"" (mb-of "a\\\""))]
                       ["dune-project unclosed block comment" d/dune-project-deps (mb-of "#| (package (depends a)) ")]
                       ["dune flat" d/dune-deps (mb-of "(library (libraries a b c)) ")]
                       ["dune atoms" d/dune-deps (mb-of "a ")]
                       ["mix tuples" d/mix-exs-deps (mb-of "{:a, \"1\", only: :dev}, ")]
                       ["mix unclosed tuples" d/mix-exs-deps (mb-of "{:a, ")]
                       ["mix interpolation" d/mix-exs-deps (mb-of "\"#{\"")]
                       ["zon deps" d/zon-deps (str ".{ .dependencies = .{ " (mb-of ".a = .{ .url = \"x\" }, ") "} }")]
                       ["zon comments" d/zon-deps (mb-of "// .dependencies = .{\n")]
                       ;; the most tokens per byte: the slowest shapes measured
                       ["mix empty tuples" d/mix-exs-deps (mb-of "{}")]
                       ["opam operators" d/opam-deps (mb-of "!")]
                       ["opam empty filters" d/opam-deps (mb-of "{}")]
                       ["dune-project empty lists" d/dune-project-deps (mb-of "()")]
                       ["zon dots" d/zon-deps (mb-of ".")]]]
    (let [ms (elapsed-ms #(f s))]
      (is (< ms 2000) (str label " took " (long ms) " ms")))))

;; ---------------------------------------------------------------------------
;; declarations

(defn- with-repo
  "Writes files ({rel-path text}) into a temp dir and calls (f dir rels)."
  [files f]
  (let [dir (fs/create-temp-dir)]
    (try
      (doseq [[rel text] files]
        (fs/create-dirs (fs/parent (fs/path dir rel)))
        (spit (str (fs/path dir rel)) text))
      (f (str dir) (sort (keys files)))
      (finally (fs/delete-tree dir)))))

(deftest declarations-carry-ecosystem-and-kind
  ;; deps-parsing [6]: ecosystem and kind come from the build file type
  (with-repo {"deps.edn" "{:deps {medley/medley {:mvn/version \"1.4.0\"}}}"
              "project.clj" "(defproject a \"1\" :dependencies [[b \"1\"]])"
              "shadow-cljs.edn" "{:dependencies [[reagent \"1.2.0\"]]}"
              "build.boot" "(set-env! :dependencies '[[c \"1\"]])"
              "bb.edn" "{:deps {d/d {:mvn/version \"1\"}}}"
              "elixir/mix.exs" "defp deps, do: [{:jason, \"~> 1.4\"}]"
              "zig/build.zig.zon" ".{ .dependencies = .{ .clap = .{ .url = \"x\" } } }"
              "ocaml/dune-project" "(package (name aoc) (depends core))"
              "ocaml/aoc.opam" "depends: [ \"core\" ]"
              "ocaml/bin/dune" "(executable (name main) (libraries core))"
              "README.md" "not a build file"}
    (fn [dir rels]
      (let [by-source (into {} (map (juxt :decl/source identity)) (d/declarations dir rels))]
        (is (= #{"deps.edn" "project.clj" "shadow-cljs.edn" "build.boot" "bb.edn" "elixir/mix.exs"
                 "zig/build.zig.zon" "ocaml/dune-project" "ocaml/aoc.opam" "ocaml/bin/dune"}
               (set (keys by-source))))
        (is (= {"deps.edn" [:maven :package] "project.clj" [:maven :package]
                "shadow-cljs.edn" [:maven :package] "build.boot" [:maven :package]
                "bb.edn" [:maven :package] "elixir/mix.exs" [:hex :package]
                "zig/build.zig.zon" [:zig :package] "ocaml/dune-project" [:opam :package]
                "ocaml/aoc.opam" [:opam :package] "ocaml/bin/dune" [:opam :library]}
               (update-vals by-source (juxt :decl/ecosystem :decl/kind))))
        (is (every? #(= :ok (:decl/status %)) (vals by-source)))
        (is (= [(dep "core" nil :main)] (:decl/deps (by-source "ocaml/bin/dune"))))))))

(deftest declarations-mark-in-repo-ocaml-names-local
  ;; deps-parsing [5] and [7]: libraries and packages the repo defines itself
  (with-repo {"dune-project" "(package (name aoc)) (package (name aoc_bin) (depends aoc core))"
              "lib/dune" "(library (name utils) (public_name aoc.utils) (libraries str))"
              "bin/dune" "(executable (name main) (libraries utils aoc.utils core))"
              "other.opam" "depends: [ \"aoc\" \"zarith\" ]"}
    (fn [dir rels]
      (let [by-source (into {} (map (juxt :decl/source :decl/deps)) (d/declarations dir rels))]
        (is (= [(dep "aoc" nil :main true) (dep "core" nil :main false)] (by-source "dune-project")))
        (is (= [(dep "utils" nil :main true) (dep "aoc.utils" nil :main true) (dep "core" nil :main false)]
               (by-source "bin/dune")))
        (is (= [(dep "str" nil :main false)] (by-source "lib/dune")))
        (is (= [(dep "aoc" nil :main true) (dep "zarith" nil :main false)] (by-source "other.opam")))))))

(deftest declarations-survive-hostile-files
  ;; security [1], [2], [3]: one bad file is :unreadable, never a thrown
  ;; error, and the whole result reads back as EDN
  (with-repo {"deps.edn" "{:deps {a/b {:git/sha 123}} :aliases {#my/tag x {:extra-deps {c/d {:mvn/version #my/tag \"1\"}}}}}"
              "project.clj" "(defproject a \"1\" :profiles {\"my dev\" {:dependencies [[cider/cider-nrepl \"0.1\"]]}})"
              "sub/deps.edn" (str "{:deps " (apply str (repeat 20000 "[")) "}")
              "sub/project.clj" (str "(defproject a \"1\" :dependencies " (apply str (repeat 20000 "[")) ")")
              "mix.exs" "defp deps, do: [{:\"weird atom\", \"1\"}]"}
    (fn [dir rels]
      (let [out (on-small-stack #(d/declarations dir rels))]
        (is (vector? out))
        (is (round-trips? out))
        (is (= {"deps.edn" :ok "project.clj" :ok "sub/deps.edn" :unreadable
                "sub/project.clj" :unreadable "mix.exs" :ok}
               (into {} (map (juxt :decl/source :decl/status)) out)))))))

(deftest declarations-skip-symlinks
  (with-repo {"real/deps.edn" "{:deps {a/a {:mvn/version \"1\"}}}"}
    (fn [dir _]
      (fs/create-sym-link (fs/path dir "deps.edn") (fs/path dir "real" "deps.edn"))
      (is (= ["real/deps.edn"] (map :decl/source (d/declarations dir ["deps.edn" "real/deps.edn"])))))))
