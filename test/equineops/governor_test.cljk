(ns equineops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [equineops.governor :as gov]
            [equineops.store :as store]))

(deftest hard-violations-no-facility-id
  (testing "Hard violation: missing facility-id"
    (let [req {}
          prop {:op :log-herd-record :effect :propose}
          s (store/mem-store)
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (seq (:violations verdict)))
      (is (some #(= :facility-not-registered (:rule %)) (:violations verdict))))))

(deftest hard-violations-unregistered-facility
  (testing "Hard violation: facility-id present but not registered"
    (let [req {:facility-id "stable-001"}
          prop {:op :log-herd-record :effect :propose}
          s (store/mem-store)
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :facility-not-registered (:rule %)) (:violations verdict))))))

(deftest hard-violations-effect-not-propose
  (testing "Hard violation: effect is not :propose"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :log-herd-record :effect :execute}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :no-execution (:rule %)) (:violations verdict))))))

(deftest hard-violations-treatment-blocked
  (testing "Hard violation: direct treatment administration is permanently blocked"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :administer-treatment :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :treatment-or-breeding-culling-blocked (:rule %)) (:violations verdict))))))

(deftest hard-violations-breeding-culling-blocked
  (testing "Hard violation: direct breeding/culling decision is permanently blocked"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :order-breeding-culling-decision :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :treatment-or-breeding-culling-blocked (:rule %)) (:violations verdict))))))

(deftest hard-violations-op-not-allowed
  (testing "Hard violation: op outside the closed allowlist"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :dispatch-robot-arm :effect :propose}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :op-not-allowed (:rule %)) (:violations verdict))))))

(deftest hard-violations-herd-count-invalid
  (testing "Hard violation: non-positive herd count"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :log-herd-record :effect :propose :count 0 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:hard? verdict))
      (is (some #(= :herd-count-invalid (:rule %)) (:violations verdict))))))

(deftest ok-herd-logging
  (testing "OK: valid herd record logging with a registered facility"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :log-herd-record :effect :propose :count 12 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:hard? verdict)))
      (is (not (:escalate? verdict))))))

(deftest escalation-health-concern
  (testing "Escalation: animal health/welfare concern ALWAYS escalates, even at high confidence"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :flag-animal-health-concern :effect :propose
                :concern "馬インフルエンザの疑い" :confidence 0.95}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict))
      (is (:high-stakes? verdict)))))

(deftest escalation-low-confidence
  (testing "Escalation: confidence below the floor"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :log-herd-record :effect :propose :count 12 :confidence 0.5}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict)))))

(deftest escalation-supply-order-high-cost
  (testing "Escalation: supply order over the (default) cost threshold"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :order-supplies :effect :propose :cost 1000 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (not (:hard? verdict)))
      (is (:escalate? verdict)))))

(deftest escalation-supply-order-category-specific-threshold
  (testing "Escalation: supply order over its category-specific threshold (tack: 1000)"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :order-supplies :effect :propose :cost 1200 :confidence 0.9
                :value {:category "tack"}}
          verdict (gov/check req nil prop s)]
      (is (:escalate? verdict))))

  (testing "OK: tack order under its higher category threshold"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :order-supplies :effect :propose :cost 800 :confidence 0.9
                :value {:category "tack"}}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))

(deftest ok-supply-order-low-cost
  (testing "OK: supply order under the cost threshold"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :order-supplies :effect :propose :cost 100 :confidence 0.9}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))

(deftest ok-schedule-veterinary-visit
  (testing "OK: scheduling a veterinary visit is a routine coordination op"
    (let [facility {:id "stable-001" :name "Test Stable"}
          s (store/mem-store {:initial-facilities {"stable-001" facility}})
          req {:facility-id "stable-001"}
          prop {:op :schedule-veterinary-visit :effect :propose :confidence 0.85}
          verdict (gov/check req nil prop s)]
      (is (:ok? verdict))
      (is (not (:escalate? verdict))))))
