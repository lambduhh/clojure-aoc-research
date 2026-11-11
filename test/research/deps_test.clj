(ns research.deps-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [research.deps :as d]))

(deftest deps-edn
  (is (= [{:dep/coord "org.clojure/clojure" :decl/version "1.11.1" :decl/scope :main}
          {:dep/coord "medley/medley" :decl/version "1.4.0" :decl/scope :main}
          {:dep/coord "io.github.cognitect-labs/test-runner" :decl/version "v0.5.1" :decl/scope :test}]
         (d/deps-edn-deps "{:deps {org.clojure/clojure {:mvn/version \"1.11.1\"} medley {:mvn/version \"1.4.0\"}}
                              :aliases {:test {:extra-deps {io.github.cognitect-labs/test-runner {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}}}}}")))
  (is (= :research.deps/unreadable (d/deps-edn-deps "{:deps"))))

(deftest project-clj
  (is (= [{:dep/coord "org.clojure/clojure" :decl/version "1.10.1" :decl/scope :main}
          {:dep/coord "instaparse/instaparse" :decl/version "1.4.10" :decl/scope :main}
          {:dep/coord "criterium/criterium" :decl/version "0.4.6" :decl/scope :profile-dev}]
         (d/project-clj-deps "(defproject aoc \"0.1.0\" :description \"x\"
                                  :dependencies [[org.clojure/clojure \"1.10.1\"] [instaparse \"1.4.10\"]]
                                  :profiles {:dev {:dependencies [[criterium \"0.4.6\"]]}})")))
  (testing "never evaluated: read-eval and unquote are not run"
    (let [marker (str (fs/create-temp-file))]
      (fs/delete marker)
      (d/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a \"1\"]] :x #=(spit \"" marker "\" \"pwned\"))"))
      (d/project-clj-deps (str "(defproject evil \"1\" :dependencies [[a ~(spit \"" marker "\" \"pwned\")]])"))
      (is (not (fs/exists? marker))))))

(deftest mix-comments-are-ignored
  (is (= [{:dep/coord "phoenix_live_view" :decl/version "~> 1.0.0-rc" :decl/scope :main}]
         (d/mix-exs-deps "  # TODO bump on release to {:phoenix_live_view, \"~> 1.0.0\"},\n  {:phoenix_live_view, \"~> 1.0.0-rc\", override: true}, # trailing\n"))))

(deftest other-build-files
  (is (= [{:dep/coord "libgraph" :decl/version "~> 0.16" :decl/scope :main}]
         (d/mix-exs-deps "defp deps do\n  [{:libgraph, \"~> 0.16\"}]\nend")))
  (is (= ["ziglyph" "clap"] (map :dep/coord (d/zon-deps ".{ .name = \"aoc\", .dependencies = .{ .ziglyph = .{ .url = \"x\" }, .clap = .{ .url = \"y\" } } }"))))
  (is (= ["dune" "containers"] (map :dep/coord (d/opam-deps "depends: [\n  \"dune\" {>= \"3.0\"}\n  \"containers\"\n]")))))

