(ns research.stats
  "Statistics for question 11, exactly as fixed in prereg amendment 4.
  Pure functions, so they run under babashka tests and the JVM analysis alike.
  Randomness always comes from a java.util.Random passed in, never a global,
  so every figure is reproducible from its seed.")

(defn median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n)
      (if (odd? n)
        (double (v (quot n 2)))
        (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0)))))

(defn mean [xs] (/ (reduce + 0.0 xs) (count xs)))

(defn sd
  "Sample standard deviation (n - 1)."
  [xs]
  (let [m (mean xs)]
    (Math/sqrt (/ (reduce + 0.0 (map #(let [d (- % m)] (* d d)) xs))
                  (dec (count xs))))))

(defn geomean-of-logs [logs] (Math/exp (mean logs)))

(defn percentile
  "Linear interpolation between closest ranks (type 7, R's default)."
  [sorted-v q]
  (let [n (count sorted-v)
        h (* (dec n) q)
        lo (long (Math/floor h))
        hi (min (dec n) (inc lo))]
    (+ (sorted-v lo) (* (- h lo) (- (sorted-v hi) (sorted-v lo))))))

(defn bootstrap-geomean-ci
  "95% percentile interval for exp(mean(logs)), resampling the logs (one per
  puzzle) with replacement."
  [^java.util.Random rng resamples logs]
  (let [v (vec logs) n (count v)
        stats (vec (sort (repeatedly resamples
                                     #(geomean-of-logs (repeatedly n (fn [] (v (.nextInt rng n))))))))]
    [(percentile stats 0.025) (percentile stats 0.975)]))

(defn- average-ranks
  "Ranks 1..n of xs (ascending), ties given their average rank. Returns
  [ranks-in-input-order tie-group-sizes]."
  [xs]
  (let [idx (vec (sort-by #(nth xs %) (range (count xs))))
        groups (partition-by #(nth xs %) idx)]
    (loop [gs groups start 1 ranks {} ties []]
      (if-let [g (first gs)]
        (let [k (count g) r (+ start (/ (dec k) 2.0))]
          (recur (rest gs) (+ start k) (into ranks (map vector g (repeat r))) (conj ties k)))
        [(mapv ranks (range (count xs))) ties]))))

(defn- normal-cdf [z]
  ;; Abramowitz & Stegun 7.1.26 erf approximation, |error| < 1.5e-7.
  (let [x (/ (Math/abs (double z)) (Math/sqrt 2.0))
        t (/ 1.0 (+ 1.0 (* 0.3275911 x)))
        y (- 1.0 (* t (Math/exp (- (* x x)))
                    (+ 0.254829592 (* t (+ -0.284496736 (* t (+ 1.421413741 (* t (+ -1.453152027 (* t 1.061405429))))))))))]
    (if (neg? z) (* 0.5 (- 1.0 y)) (* 0.5 (+ 1.0 y)))))

(defn wilcoxon-signed-rank
  "Two-sided one-sample Wilcoxon signed-rank test of ds against 0.
  Zeros discarded, average ranks for ties, normal approximation with
  tie-corrected variance and a 0.5 continuity correction (prereg amendment 4)."
  [ds]
  (let [nz (vec (remove zero? ds))
        n (count nz)
        [ranks ties] (average-ranks (mapv #(Math/abs (double %)) nz))
        w+ (reduce + 0.0 (keep-indexed (fn [i d] (when (pos? d) (ranks i))) nz))
        mu (/ (* n (inc n)) 4.0)
        var (- (/ (* n (inc n) (inc (* 2 n))) 24.0)
               (/ (reduce + 0.0 (map #(- (* % % %) %) ties)) 48.0))
        z (if (pos? var)
            (/ (- (Math/abs (- w+ mu)) 0.5) (Math/sqrt var))
            0.0)
        z (max z 0.0)]
    {:n n :zeros-dropped (- (count ds) n) :w+ w+ :z (if (< w+ mu) (- z) z)
     :p (min 1.0 (* 2.0 (- 1.0 (normal-cdf z))))}))

(defn cohens-d [logs] (/ (mean logs) (sd logs)))

(defn p-band
  "Nanz & Furia's wording, as pre-registered."
  [p]
  (cond (< p 0.01) "significantly different"
        (< p 0.05) "tends to be different"
        :else "no evidence of a difference"))

(defn d-band [d]
  (let [a (Math/abs (double d))]
    (cond (< a 0.05) "vanishing" (< a 0.3) "small" (< a 0.7) "medium" :else "large")))

(defn sample-without-replacement
  "k distinct elements of v, by partial Fisher-Yates on a copy."
  [^java.util.Random rng k v]
  (let [a (object-array v) n (alength a)]
    (dotimes [i k]
      (let [j (+ i (.nextInt rng (- n i)))
            t (aget a i)]
        (aset a i (aget a j))
        (aset a j t)))
    (vec (take k a))))

(defn equal-size-min
  "Median, over `repeats` draws, of the minimum of k values drawn without
  replacement from xs (prereg amendment 2)."
  [^java.util.Random rng repeats k xs]
  (median (repeatedly repeats #(apply min (sample-without-replacement rng k xs)))))
