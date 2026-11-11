(ns research.clone-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [research.cache :as cache]
            [research.clone :as clone]))

(def repos [{:repo/id 1 :repo/size-kb 10} {:repo/id 2 :repo/size-kb 10}
            {:repo/id 3 :repo/size-kb 10} {:repo/id 4 :repo/size-kb 10}])

(deftest read-log-latest-wins
  (let [f (str (fs/create-temp-file))]
    (spit f (str (pr-str {:repo/id 1 :clone/status :failed}) "\n\n"
                 (pr-str {:repo/id 1 :clone/status :cloned}) "\n"))
    (is (= {1 {:repo/id 1 :clone/status :cloned}} (clone/read-log f)))
    (is (= {} (clone/read-log "/no/such/file")))))

(deftest pending-selection
  (let [log {1 {:clone/status :cloned} 2 {:clone/status :failed} 3 {:clone/status :missing}}]
    (is (= [4] (map :repo/id (clone/pending repos log {}))))
    (is (= [2 4] (map :repo/id (clone/pending repos log {:retry-failed true}))))))

(deftest size-cap
  (is (clone/over-size-cap? {:repo/size-kb 10241}))
  (is (not (clone/over-size-cap? {:repo/size-kb 10240}))))

(deftest errors
  (is (= :missing (clone/classify-error "remote: Repository not found.")))
  (is (= :missing (clone/classify-error "fatal: could not read Username for 'https://github.com'")))
  (is (= :failed (clone/classify-error "fatal: early EOF"))))

(deftest repo-dir-is-keyed-by-id
  (is (.endsWith (cache/repo-dir {:repo/lang :clojure :repo/id 42}) "/repos/clojure/42"))
  (is (thrown? Exception (cache/repo-dir {:repo/lang :clojure :repo/id "../x"}))))
