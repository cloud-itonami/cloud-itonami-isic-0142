(ns equineops.facts-test
  (:require [clojure.test :refer [deftest is are testing]]
            [equineops.facts :as facts]))

(deftest supply-category-lookup
  (testing "Lookup valid supply category"
    (let [c (facts/supply-category-by-id "feed")]
      (is (= "feed" (:id c)))
      (is (= "飼料" (:name c)))))

  (testing "Lookup invalid supply category"
    (is (nil? (facts/supply-category-by-id "unknown")))))

(deftest supply-category-cost-thresholds
  (testing "Category-specific cost thresholds"
    (are [id expected] (= expected (:cost-threshold (facts/supply-category-by-id id)))
      "feed"               500
      "veterinary-supply"  500
      "tack"               1000)))

(deftest default-cost-threshold-value
  (testing "Default fallback threshold matches the conservative baseline"
    (is (= 500 facts/default-cost-threshold))))

(deftest species-lookup
  (testing "Lookup valid species"
    (are [id expected-name] (= expected-name (:name (facts/species-by-id id)))
      "horse"  "馬"
      "donkey" "ロバ"
      "mule"   "ラバ"))

  (testing "Lookup invalid species"
    (is (nil? (facts/species-by-id "unknown")))))

(deftest health-concern-lookup
  (testing "Lookup valid health concern"
    (let [c (facts/health-concern-by-id "equine-influenza")]
      (is (= "equine-influenza" (:id c)))
      (is (true? (:notifiable c)))))

  (testing "Lookup a non-notifiable health concern"
    (let [c (facts/health-concern-by-id "colic")]
      (is (= "colic" (:id c)))
      (is (false? (:notifiable c)))))

  (testing "Lookup invalid health concern"
    (is (nil? (facts/health-concern-by-id "unknown")))))
