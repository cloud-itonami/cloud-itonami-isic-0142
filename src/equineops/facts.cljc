(ns equineops.facts
  "Reference facts for equine-facility operations coordination: supply
  category cost policy, species/type classification, and equine health
  concern reference vocabulary. This namespace contains pure lookup
  functions for domain reference data -- the Governor and Advisor consult
  these instead of inventing thresholds. Mirrors `cattleops.facts`
  (cloud-itonami-isic-0141) / `swineops.facts` (cloud-itonami-isic-0145)
  in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (stable operator/veterinarian)."
  {"feed"
   {:id "feed" :name "飼料" :cost-threshold 500}

   "veterinary-supply"
   {:id "veterinary-supply" :name "獣医用品" :cost-threshold 500}

   "tack"
   {:id "tack" :name "馬具" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def species
  "Species/types this actor's facility/herd records may cover (ISIC 0142:
  raising of horses and other equines)."
  {"horse"  {:id "horse"  :name "馬"}
   "donkey" {:id "donkey" :name "ロバ"}
   "mule"   {:id "mule"   :name "ラバ"}})

(defn species-by-id [id]
  (get species id))

(def health-concerns
  "Reference vocabulary for common equine health/welfare concerns this
  actor's `:flag-animal-health-concern` op may cite (e.g. suspected
  equine influenza on a neighboring premises, or acute colic). Purely
  descriptive -- citing a concern (or leaving it free text) NEVER changes
  the Governor's disposition: EVERY flagged concern always escalates for
  veterinary/stable-operator review
  (`equineops.governor/always-escalate-ops`), regardless of the concern's
  `:notifiable` status or apparent severity. This actor has no authority
  to declare an outbreak, order treatment, or contact animal-health
  authorities -- it only surfaces the observation for human/veterinary
  judgment."
  {"equine-influenza" {:id "equine-influenza" :name "馬インフルエンザ (Equine Influenza)" :notifiable true}
   "colic"            {:id "colic"            :name "疝痛 (Colic)" :notifiable false}
   "strangles"        {:id "strangles"        :name "腺疫 (Strangles)" :notifiable true}
   "laminitis"        {:id "laminitis"        :name "蹄葉炎 (Laminitis)" :notifiable false}})

(defn health-concern-by-id [id]
  (get health-concerns id))
