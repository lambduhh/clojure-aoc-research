(ns research.stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [research.stats :as s]))

(defn- close? [eps a b] (< (Math/abs (- (double a) (double b))) eps))

(deftest wilcoxon-matches-scipy
  ;; Reference: scipy 1.x, wilcoxon(ds, zero_method='wilcox', correction=True,
  ;; method='approx'). Both inputs have ties and zeros, which is where
  ;; implementations disagree. scipy's statistic is min(W+, W-).
  (testing "ties and one zero"
    (let [r (s/wilcoxon-signed-rank [0.5 -0.2 0.3 0.3 -0.1 0.7 0.0 0.4 -0.3 0.2 0.6 0.1])]
      (is (= 11 (:n r)))
      (is (= 1 (:zeros-dropped r)))
      (is (close? 1e-9 55.0 (:w+ r)))      ; W- = 11, scipy's statistic
      (is (close? 1e-6 0.055202974168669955 (:p r)))))
  (testing "negative direction, two zeros"
    (let [r (s/wilcoxon-signed-rank [-1 -2 -3 1 2 -4 -5 -6 -2 -1 0 0 3])]
      (is (close? 1e-9 14.5 (:w+ r)))
      (is (neg? (:z r)))
      (is (close? 1e-6 0.10793170787976822 (:p r))))))

(deftest basics
  (is (= 2.0 (s/median [3 1 2])))
  (is (= 2.5 (s/median [4 1 3 2])))
  (is (nil? (s/median [])))
  (is (close? 1e-12 1.0 (s/sd [1 2 3])))
  (is (close? 1e-12 2.0 (s/cohens-d [1 2 3])))
  (is (close? 1e-12 2.0 (s/geomean-of-logs [(Math/log 1) (Math/log 4)])))
  (is (= 5.5 (s/percentile [1 10] 0.5)))
  (is (= "tends to be different" (s/p-band 0.03)))
  (is (= "medium" (s/d-band -0.5))))

(deftest seeded-and-reproducible
  (let [run #(s/bootstrap-geomean-ci (java.util.Random. 20261117) 1000 [0.1 -0.2 0.3 0.05 -0.4])]
    (is (= (run) (run))))
  (let [draw (s/sample-without-replacement (java.util.Random. 1) 10 (range 10))]
    (is (= (set (range 10)) (set draw)) "k = n draws every element once"))
  (testing "equal-size minimum of a constant sample is that constant"
    (is (= 7.0 (s/equal-size-min (java.util.Random. 1) 50 10 (repeat 30 7))))))
