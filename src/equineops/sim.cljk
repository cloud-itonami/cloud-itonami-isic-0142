(ns equineops.sim
  "Simple simulation/demo runner for the Equine-Facility Operations
  Coordinator actor. Used to validate that the actor flow compiles and
  basic proposal flow works. Mirrors `cattleops.sim`
  (cloud-itonami-isic-0141) / `swineops.sim` (cloud-itonami-isic-0145)."
  (:require [equineops.operation :as operation]
            [equineops.store :as store]))

(defn demo
  "Run a simple demo scenario: register a facility, propose a herd-record
  log, and check the disposition flow."
  []
  (let [;; Create store with a registered facility
        st (store/mem-store
            {:initial-facilities
             {"stable-001"
              {:id "stable-001"
               :name "Test Stable"
               :species "horse"}}})

        ;; Build actor
        actor (operation/build st)

        ;; Create a request to log a herd record
        request {:op :log-herd-record
                 :facility-id "stable-001"
                 :count 12
                 :health-status "healthy"}

        ;; Context with phase 0 (simulation)
        context {:actor-id "equine-ops-01"
                 :role :stable-operator
                 :phase :phase-0}]

    (println "=== Equine-Facility Operations Coordinator Demo ===")
    (println "Demo facility: stable-001")
    (println "Request: log-herd-record")
    (println "Phase: phase-0 (simulation)")
    (println "Expected: escalate (phase-0 forces human review of all commits)")
    (println)
    (let [result (actor request context)]
      (println "Result disposition:" (:disposition result))
      result)))

(defn -main
  "clojure -M:run entrypoint."
  [& _args]
  (demo))

(comment
  ;; In a real REPL:
  (demo)
)
