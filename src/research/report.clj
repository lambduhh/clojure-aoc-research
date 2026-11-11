(ns research.report
  "Wrap a script's plain-text output as an OKF Markdown document in
  docs/results/, so results carry frontmatter and pass bb docs:check.

  bb -cp src analysis/newcomers.bb | bb results:wrap --name 2026-11-12-newcomers \\
     --title \"Newcomer follow-on\" --description \"...\" --source analysis/newcomers.bb"
  (:require [clojure.string :as str]))

(defn- yaml-str [s] (str "\"" (str/replace (str s) "\"" "\\\"") "\""))

(defn document
  "Frontmatter + heading + description + the text in a fenced block."
  [{:keys [name title description source today]} text]
  (str "---\n"
       "type: Research\n"
       "title: " (yaml-str title) "\n"
       "description: " (yaml-str description) "\n"
       "tags: [advent-of-code, results]\n"
       "created: " today "\nmodified: " today "\n"
       "source: " (yaml-str source) "\n"
       "ai_assisted: \"Claude Opus 5.5 via Claude Code\"\n"
       "session: " (or session "unrecorded") "\ntools: [babashka, clojure, Datomic Local]\nagents_skills: []\n"
       "review_maturity: L0 (AI-generated)\n"
       "review_note: \"Script output, not yet human-reviewed.\"\n"
       "---\n\n# " title "\n\n" description "\n\nProduced by: " source ".\n\n"
       "```text\n" (str/trimr text) "\n```\n"))

(defn -main [& args]
  (let [opts (into {} (for [[k v] (partition 2 args)] [(keyword (str/replace k #"^--" "")) v]))
        today (str (java.time.LocalDate/now))
        missing (remove opts [:name :title :description :source])]
    (when (seq missing)
      (binding [*out* *err*] (println "missing flags:" (str/join ", " (map #(str "--" (name %)) missing))))
      (System/exit 2))
    (let [out (str "docs/results/" (:name opts) ".md")]
      (spit out (document (assoc opts :today today) (slurp *in*)))
      (println "wrote" out))))
