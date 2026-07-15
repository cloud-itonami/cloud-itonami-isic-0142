(ns equineops.governor
  "Equine Facility Operations Governor -- the independent compliance layer
  that earns the EquineOpsAdvisor the right to commit. The LLM has no
  notion of:
    - Whether the facility a proposal targets is actually registered
    - Whether a proposal is a real actuation (`:effect :propose` only --
      this actor NEVER directly handles animals, administers treatment,
      or executes anything)
    - Whether an op is inside this actor's closed coordination allowlist
    - Whether a logged herd count is a plausible positive observation
    - Whether a supply-order's cost exceeds the escalation threshold

  This MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  This actor is a back-office OPERATIONS COORDINATOR only -- direct
  animal handling and veterinary treatment/breeding/culling decisions are
  categorically outside its authority (stable operator/veterinarian
  exclusive). The Governor enforces that boundary structurally, not by
  trusting the advisor's judgment. This actor's scope is raising/breeding
  operations coordination -- racing/gambling activities are categorically
  out of scope and have no corresponding op in the closed allowlist.

  CRITICAL: Any proposal to flag an animal health/welfare concern (e.g.
  suspected equine influenza, colic, injury) ALWAYS escalates to a human
  (stable operator/veterinarian) for final sign-off. The LLM's confidence
  is never sufficient for animal-welfare decisions.

  Hard violations (always HOLD, no override, permanent):
    1. Facility/stable record not registered (facility-id missing or
       unknown to Store)
    2. Proposal `:effect` is not `:propose` (no direct execution, ever)
    3. Op is `:administer-treatment` or `:order-breeding-culling-decision`
       -- direct treatment administration and breeding/culling decisions
       are PERMANENTLY blocked regardless of proposal content or
       confidence
    4. Op is outside the closed proposal-op allowlist
    5. `:log-herd-record` with a non-positive herd count

  Soft gates (always escalate for human):
    - `:flag-animal-health-concern` -- ALWAYS escalates
    - `:order-supplies` above its category cost threshold
    - Low confidence

  This design mirrors `cattleops.governor` (cloud-itonami-isic-0141) /
  `swineops.governor` (cloud-itonami-isic-0145) but specializes equine
  back-office coordination concerns (facility/stable registration, closed
  op allowlist, treatment/breeding/culling exclusion, cost threshold,
  animal-health-concern escalation) rather than generic ranching or
  swine-farm concerns."
  (:require [equineops.facts :as facts]
            [equineops.registry :as registry]
            [equineops.store :as store]))

(def confidence-floor 0.7)

(def blocked-ops
  "Direct treatment administration and breeding/culling decisions sit
  outside this actor's coordination-only authority. ALWAYS a hard,
  permanent block -- never escalate, never override, regardless of
  confidence or cites. This actor never decides which animals are bred
  together, which are withdrawn from a breeding program, or which are
  culled/euthanized -- those are the stable operator/veterinarian's
  exclusive authority."
  #{:administer-treatment :order-breeding-culling-decision})

(def known-ops
  "The closed allowlist of proposal ops this actor may make -- all
  `:effect :propose` (see ADR domain design)."
  #{:log-herd-record :schedule-veterinary-visit
    :flag-animal-health-concern :order-supplies})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off even when the Governor finds no
  hard violation and confidence is high. Flagging an animal health/welfare
  concern is never something this actor resolves autonomously."
  #{:flag-animal-health-concern})

(def all-recognized-ops
  "known-ops (allowed to proceed) union blocked-ops (recognized but
  permanently forbidden). Anything outside this union is an unknown op --
  a HARD violation, not a silent no-op."
  (into known-ops blocked-ops))

;; ----------------------------- checks -----------------------------

(defn- facility-violations
  "A proposal referencing an unregistered (or absent) facility-id is a
  HARD violation -- never act on behalf of a facility/stable this actor
  cannot independently verify."
  [{:keys [facility-id]} st]
  (when-not (store/registered-facility st facility-id)
    [{:rule :facility-not-registered
      :detail (str "facility-id " (pr-str facility-id) " は登録済み施設として確認できない -- 施設登録前の提案は進められない")}]))

(defn- execution-violations
  "This actor never executes directly. Any proposal whose `:effect` isn't
  `:propose` is a HARD violation, independent of what op it claims."
  [proposal]
  (when-not (= :propose (:effect proposal))
    [{:rule :no-execution
      :detail "提案の :effect は :propose でなければならない -- governor は直接実行/作動を許可しない"}]))

(defn- treatment-or-breeding-culling-violations
  "Direct treatment administration and breeding/culling decisions are a
  HARD, permanent block -- economic and veterinary authority remains
  exclusively human."
  [proposal]
  (when (contains? blocked-ops (:op proposal))
    [{:rule :treatment-or-breeding-culling-blocked
      :detail (str (:op proposal) " は直接治療の実施または繁殖/淘汰判断であり、恒久的にブロックされる -- 獣医/施設運営者の専権事項")}]))

(defn- unknown-op-violations
  "Enforce the closed proposal-op allowlist independently of the
  advisor's claim -- an op outside `all-recognized-ops` is a HARD
  violation, never a silent pass-through."
  [proposal]
  (when-not (contains? all-recognized-ops (:op proposal))
    [{:rule :op-not-allowed
      :detail (str (:op proposal) " はクローズドallowlist外の操作")}]))

(defn- herd-count-invalid-violations
  "For `:log-herd-record`, INDEPENDENTLY verify the logged count is a
  plausible positive observation via
  `registry/herd-count-non-positive?`. Evaluated only when a `:count` is
  present on the proposal."
  [proposal]
  (when (and (= :log-herd-record (:op proposal))
             (contains? proposal :count)
             (registry/herd-count-non-positive? (:count proposal)))
    [{:rule :herd-count-invalid
      :detail (str "頭数 " (:count proposal) " は正の数でなければならない -- 記録提案は進められない")}]))

(defn- cost-threshold-for
  "Resolve the escalation threshold for a supply-order proposal: the
  category-specific threshold from `equineops.facts` if the category is
  known, else the conservative default."
  [proposal]
  (let [category (get-in proposal [:value :category])
        c (and category (facts/supply-category-by-id category))]
    (or (:cost-threshold c) facts/default-cost-threshold)))

(defn check
  "Censors an EquineOpsAdvisor proposal against the Governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (facility-violations request st)
                           (execution-violations proposal)
                           (treatment-or-breeding-culling-violations proposal)
                           (unknown-op-violations proposal)
                           (herd-count-invalid-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (registry/confidence-below-floor? conf confidence-floor)
        cost (:cost proposal)
        high-cost? (boolean (and cost (registry/cost-exceeds-threshold?
                                        cost (cost-threshold-for proposal))))
        always-escalate? (contains? always-escalate-ops (:op proposal))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not high-cost?) (not always-escalate?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? high-cost? always-escalate?))
     :high-stakes? (boolean (or high-cost? always-escalate?))}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:facility-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
