(ns equineops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack -- `equineops.operation/build`
  -> `equineops.advisor` -> `equineops.governor` -> `equineops.phase`,
  reading facility state back out of `equineops.store` -- and renders
  whatever that run actually produced.

  Nothing on the page is typed by hand:

    - facility rows are read BACK through `equineops.store/registered-facility`
      after the run, joined against `equineops.facts/species-by-id`;
    - the phase-gate matrix is produced by CALLING `equineops.phase/gate`
      for every (phase x op) pair, not described in prose;
    - the HARD-hold rows are grouped out of the run's real `:governor-hold`
      facts, `:detail` strings included verbatim as the governor wrote them;
    - the supply-threshold rows read `equineops.facts/supply-categories`
      and join in the disposition each category ACTUALLY received this run;
    - the approver-attribution section is DERIVED at render time by probing
      the real records, audit facts and Store protocol for approver keys
      (see `approver-probe`) -- it is not a hardcoded claim about this
      repo, so it will start reporting `retained` on its own the day the
      store keeps an approver.

  NOTE on the pipeline shape: unlike the langgraph-driven peers
  (e.g. `cloud-itonami-isic-6411`), this repo's `equineops.operation/build`
  returns a plain synchronous invoke fn, NOT a `langgraph.graph`
  StateGraph -- see that namespace's own docstring (\"langgraph-clj
  StateGraph integration is deferred\"). This renderer drives the actor
  exactly the way the repo's own `equineops.sim` demo driver does
  (`clojure -M:dev:run`, run BEFORE this file was written and confirmed
  to produce `:escalate` at phase-0), rather than pretending a graph
  runtime is wired in that is not.

  Build-time invariants (`-main` THROWS, it does not warn):
    1. the run must produce at least one real `:governor-hold` fact
       carrying at least one violation -- a phase-gate `:unknown-phase`
       hold writes the same `:t` with an EMPTY `:violations`, and that is
       not a governor HARD hold;
    2. it must exercise at least `min-distinct-hard-rules` DISTINCT HARD
       rules -- an evidence floor, so a governor change that silently
       stops firing a check fails the build instead of quietly shrinking
       the page;
    3. no run that HARD-held may ALSO have asked a human -- the \"a HARD
       hold never reaches a human\" claim is measured per-run, not asserted;
    4. every HARD-rule row rendered must trace to a rule the run actually
       produced -- the section cannot outlive the run that justified it;
    5. at least one op must have COMMITTED, otherwise the approver probe
       would report \"no approver found\" for the vacuous reason that there
       was no record to look in;
    6. at least one op must have ESCALATED, otherwise the human-approval
       queue renders as a legitimate-looking empty table.

  Deterministic: no timestamps, no random ids, every map iterated in
  sorted order -- byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs into scratch files).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [equineops.advisor :as advisor]
            [equineops.facts :as facts]
            [equineops.governor :as governor]
            [equineops.operation :as operation]
            [equineops.phase :as phase]
            [equineops.store :as store]))

(def min-distinct-hard-rules
  "Evidence floor for invariant 2. The scenario below drives five distinct
  HARD governor rules -- every hard rule `equineops.governor/check` can
  produce. If a governor/advisor change makes fewer of them fire, the
  build fails rather than rendering a quietly thinner page. Raise this
  when the scenario grows; never lower it to make a build pass."
  5)

;; ----------------------------- seed data -----------------------------

(def seed-facilities
  "The facilities this scenario registers in the Store before any
  proposal is made. `equineops.store` is a facility REGISTRY -- it has no
  `seed-db` of its own (see `equineops.sim`, which seeds inline the same
  way), so the seed lives here and every rendered facility row is read
  BACK out of the store through the protocol after the run rather than
  echoed from this map. `:species` values are ids into
  `equineops.facts/species`; the renderer joins them, so a typo here
  renders as an unresolved id instead of a plausible-looking name."
  (sorted-map
   "stable-001" {:id "stable-001" :name "蒼馬ステーブル (Aoba Stable)"
                 :species "horse" :region "Hokkaido, JP" :registered-head 24}
   "stable-002" {:id "stable-002" :name "陽光牧場 (Yoko Ranch)"
                 :species "donkey" :region "Kumamoto, JP" :registered-head 9}
   "stable-003" {:id "stable-003" :name "北嶺育成牧場 (Kitamine Training Farm)"
                 :species "horse" :region "Aomori, JP" :registered-head 41}))

(def ^:private operator
  "The human operator context every run in this scenario carries, except
  where a run deliberately exercises an earlier rollout phase."
  {:actor-id "equine-ops-01" :role :stable-operator :phase :phase-3})

;; ----------------------------- the real run -----------------------------

(defrecord DirectExecutionAdvisor [inner]
  advisor/Advisor
  (-advise [_advisor st request]
    ;; A real Advisor implementation injected through `operation/build`'s
    ;; documented `:advisor` seam, which attempts a DIRECT actuation
    ;; instead of a proposal. The point is to watch the Governor's
    ;; `:no-execution` invariant reject an advisor that actually tried it
    ;; -- the mock advisor never emits anything but `:effect :propose`, so
    ;; without a misbehaving advisor that rule could only ever be claimed,
    ;; never observed firing.
    (assoc (advisor/-advise inner st request) :effect :execute)))

(defn- exec!
  "One supervised operation = one actor run. Accumulates the run into
  `runs` so the renderer can read each run's own audit channel, record and
  verdict -- this repo's Store keeps no ledger of its own (it answers
  exactly one question, `registered-facility`), so the per-run `:audit`
  vectors ARE the audit trail and there is no second source."
  [runs actor label request & [ctx]]
  (let [context (merge operator ctx)
        r (actor request context)]
    (swap! runs conj (merge (select-keys request [:op :facility-id])
                            {:label label
                             :phase (:phase context)
                             :context context
                             :request request
                             :disposition (:disposition r)
                             :audit (vec (:audit r))
                             :record (:record r)
                             :verdict (:verdict r)}))
    r))

(defn run-demo!
  "Runs a freshly seeded store through a scenario that reaches every
  disposition this actor can produce. Returns `{:store .. :runs [..]}` --
  every field the renderer reads below is real advisor/governor/phase-gate
  output.

  Clean commits (phase 3, governor clean, above the confidence floor):
    - stable-001 logs a herd record of 24 head;
    - stable-001 schedules a routine veterinary visit;
    - stable-001 orders feed at EXACTLY its 500 threshold -- the boundary
      is inclusive (`registry/cost-exceeds-threshold?` is `>`), so this
      commits while its 1250 sibling below does not;
    - stable-002 orders tack at 900, which would have escalated under the
      default 500 threshold but sits under tack's own 1000.

  SOFT gates (escalate to a human, never held):
    - stable-002's 1250 veterinary-supply order exceeds its 500 threshold;
    - stable-003 flags colic, and separately flags equine influenza --
      BOTH escalate, which is the measurable form of the claim in
      `equineops.facts/health-concerns` that a concern's `:notifiable`
      status never changes the disposition;
    - the same clean herd record replayed at phase-0 escalates on
      `:phase-0-simulation-only`, and a health-concern flag at phase-1
      escalates on `:phase-1-always-escalate` -- the phase gate adding
      caution the governor did not require.

  HARD holds (never reach a human; all five governor rules):
    - `:facility-not-registered` -- stable-999 was never registered;
    - `:treatment-or-breeding-culling-blocked` -- twice, once for
      `:administer-treatment` and once for `:order-breeding-culling-decision`;
    - `:op-not-allowed` -- `:place-racing-bet`, the racing/gambling
      activity the governor docstring puts categorically out of scope;
    - `:herd-count-invalid` -- twice, a zero count and a negative count;
    - `:no-execution` -- an advisor that emitted `:effect :execute`;
    - and one run that violates TWO rules at once (unregistered facility
      AND a zero count), to show violations accumulating rather than
      short-circuiting."
  []
  (let [st (store/mem-store {:initial-facilities (into {} seed-facilities)})
        runs (atom [])
        actor (operation/build st)
        rogue (operation/build st {:advisor (->DirectExecutionAdvisor
                                             (advisor/mock-advisor))})]

    ;; --- clean commits at phase 3 ----------------------------------------
    (exec! runs actor "herd-record-clean"
           {:op :log-herd-record :facility-id "stable-001"
            :count 24 :health-status "healthy"})
    (exec! runs actor "vet-visit-routine"
           {:op :schedule-veterinary-visit :facility-id "stable-001"
            :requested-date "2026-09-02" :reason "annual-vaccination"})
    (exec! runs actor "feed-order-at-threshold"
           {:op :order-supplies :facility-id "stable-001"
            :category "feed" :cost 500})
    (exec! runs actor "tack-order-under-category-threshold"
           {:op :order-supplies :facility-id "stable-002"
            :category "tack" :cost 900})

    ;; --- soft gates: escalate to a human ----------------------------------
    (exec! runs actor "vet-supply-order-over-threshold"
           {:op :order-supplies :facility-id "stable-002"
            :category "veterinary-supply" :cost 1250})
    (exec! runs actor "health-concern-colic"
           {:op :flag-animal-health-concern :facility-id "stable-003"
            :concern "colic"})
    (exec! runs actor "health-concern-equine-influenza"
           {:op :flag-animal-health-concern :facility-id "stable-003"
            :concern "equine-influenza"})

    ;; --- soft gates added by the PHASE gate, not the governor -------------
    (exec! runs actor "herd-record-replayed-at-phase-0"
           {:op :log-herd-record :facility-id "stable-001"
            :count 24 :health-status "healthy"}
           {:phase :phase-0})
    (exec! runs actor "health-concern-at-phase-1"
           {:op :flag-animal-health-concern :facility-id "stable-001"
            :concern "laminitis"}
           {:phase :phase-1})

    ;; --- HARD holds -------------------------------------------------------
    (exec! runs actor "unregistered-facility"
           {:op :log-herd-record :facility-id "stable-999"
            :count 12 :health-status "healthy"})
    (exec! runs actor "direct-treatment-attempt"
           {:op :administer-treatment :facility-id "stable-001"
            :treatment "phenylbutazone"})
    (exec! runs actor "breeding-culling-decision-attempt"
           {:op :order-breeding-culling-decision :facility-id "stable-002"
            :decision "withdraw-from-breeding-program"})
    (exec! runs actor "racing-wager-attempt"
           {:op :place-racing-bet :facility-id "stable-003" :stake 50000})
    (exec! runs actor "herd-count-zero"
           {:op :log-herd-record :facility-id "stable-001"
            :count 0 :health-status "healthy"})
    (exec! runs actor "herd-count-negative"
           {:op :log-herd-record :facility-id "stable-002"
            :count -3 :health-status "healthy"})
    (exec! runs rogue "advisor-attempted-direct-execution"
           {:op :log-herd-record :facility-id "stable-001"
            :count 18 :health-status "healthy"})
    (exec! runs actor "unregistered-facility-and-zero-count"
           {:op :log-herd-record :facility-id "stable-404"
            :count 0 :health-status "healthy"})

    {:store st :runs @runs}))

;; ----------------------------- derivation -----------------------------

(defn ledger
  "The append-only decision-fact log for this scenario: every run's audit
  channel, concatenated in the order the actor produced them. This repo's
  Store keeps no ledger, so this IS the ledger -- assembled from real run
  output, never from a second source."
  [runs]
  (into [] (mapcat :audit) runs))

(defn hard-holds
  "The `:governor-hold` facts that carry at least one governor violation.
  The phase gate writes the SAME `:t` with an EMPTY `:violations` when it
  refuses an unknown phase, so `(= :governor-hold (:t f))` alone would
  over-count. Discriminating here rather than at each call site keeps the
  page and the build invariant reading exactly the same set."
  [facts]
  (filterv #(and (= :governor-hold (:t %)) (seq (:violations %))) facts))

(defn hard-rule-groups
  "HARD holds grouped by the rule that fired:
  `{:rule .. :times .. :facilities [..] :detail ..}`. `:detail` is the
  governor's own string, taken verbatim from the first occurrence."
  [facts]
  (->> (hard-holds facts)
       (mapcat (fn [f] (map #(assoc % :subject (:subject f)) (:violations f))))
       (group-by :rule)
       (map (fn [[rule vs]]
              {:rule rule
               :times (count vs)
               :facilities (vec (sort (distinct (map #(or (:subject %) "(none)") vs))))
               :detail (:detail (first vs))}))
       (sort-by (comp str :rule))
       vec))

(def approver-key-candidates
  "Keys that, if present anywhere in a committed record, an audit fact or
  a store-held facility record, would identify the HUMAN who approved an
  escalated operation. Probed for -- never assumed present, never assumed
  absent."
  (sorted-set :approved-by :approver :approval :approval-by :decided-by
              :signed-by :human-approver :reviewer :by))

(defn- approver-keys-in
  "Every approver-candidate key actually present in `m` or in its nested
  `:value` / `:payload` maps."
  [m]
  (let [scan (fn [x] (when (map? x)
                       (filter #(contains? approver-key-candidates %) (keys x))))]
    (into (sorted-set)
          (concat (scan m) (scan (:value m)) (scan (:payload m))))))

(defn approver-probe
  "MEASURES, at render time, whether the approver of an escalated
  operation survives anywhere this actor can read it back. Walks:

    - every commit record `operation/run-operation` actually returned;
    - every audit fact the runs actually produced;
    - every facility record read back through `store/registered-facility`;
    - the Store protocol's own signature set, which is what decides
      whether a commit register could exist here at all.

  Returns what was found, not a verdict typed by hand. The day the store
  or the operation flow starts retaining an approver, this probe reports
  it without anyone editing this namespace."
  [{:keys [store runs]}]
  (let [records (filterv some? (map :record runs))
        facts (ledger runs)
        facility-records (filterv some?
                                  (map #(store/registered-facility store %)
                                       (keys seed-facilities)))
        protocol-fns (vec (sort (map name (keys (:sigs store/Store)))))
        found (fn [ms] (into (sorted-set) (mapcat approver-keys-in ms)))
        in-records (found records)
        in-facts (found facts)
        in-store (found facility-records)
        escalations (filterv #(= :approval-requested (:t %)) facts)
        commits (filterv #(= :committed (:t %)) facts)]
    {:records-scanned (count records)
     :facts-scanned (count facts)
     :facility-records-scanned (count facility-records)
     :store-protocol-fns protocol-fns
     :approver-keys-in-records in-records
     :approver-keys-in-facts in-facts
     :approver-keys-in-store in-store
     :escalations escalations
     :commits commits
     ;; `:actor` on a commit fact is the PROPOSING actor-id from the
     ;; operator context -- measured here so the page can say so instead
     ;; of letting a reader mistake it for an approver.
     :commit-actor-ids (into (sorted-set) (keep :actor commits))
     :context-actor-ids (into (sorted-set) (keep #(get-in % [:context :actor-id]) runs))
     :retained? (boolean (seq (into in-records (into in-facts in-store))))}))

(defn- disposition-by-category
  "For each supply category, the disposition its order ACTUALLY received
  this run (or nil if this scenario never ordered from it) -- joined so
  the threshold table shows the rule being applied, not just declared."
  [runs]
  (into {}
        (for [r runs
              :when (= :order-supplies (:op r))
              :let [cat (get-in r [:request :category])]
              :when cat]
          [cat {:cost (get-in r [:request :cost])
                :facility (:facility-id r)
                :disposition (:disposition r)}])))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- conf2
  "Two-decimal confidence, formatted under `Locale/ROOT`. Clojure's
  `format` uses the DEFAULT locale, which renders 0.9 as `0,90` on a
  comma-decimal machine -- that would make the page's bytes depend on who
  built it, which is exactly the determinism claim in this namespace's
  docstring."
  [x]
  (String/format java.util.Locale/ROOT "%.2f" (into-array Object [(double x)])))

(defn- code* [v] (str "<code>" (esc v) "</code>"))

(defn- pill [class label]
  (str "<span class=\"" class "\">" (esc label) "</span>"))

(defn- disposition-pill [d]
  (case d
    :commit   (pill "ok" "commit")
    :escalate (pill "warn" "escalate → human")
    :hold     (pill "critical" "HARD hold")
    (pill "muted" (str d))))

(defn- cells [xs] (str/join "" (map #(str "<td>" % "</td>") xs)))
(defn- row [xs] (str "        <tr>" (cells xs) "</tr>"))
(defn- rows [xs] (str/join "\n" (map row xs)))

(defn- section [title lede headers body-rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join "" (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n" body-rows "\n      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; --- phase labels (the ids `equineops.phase/gate` implements) ------------

(def phase-labels
  "The four rollout phases `equineops.phase/gate` implements. This
  namespace has no `phases` map to read (unlike some peers -- the phases
  live in `gate`'s own `case` arms), so the ids are listed here and the
  BEHAVIOUR of each is computed by CALLING `phase/gate`: the labels are
  documentation, every cell under them is measurement."
  (sorted-map
   :phase-0 "simulation/test only"
   :phase-1 "supervised"
   :phase-2 "reduced supervision"
   :phase-3 "full autonomy"))

;; --- rows ---------------------------------------------------------------

(defn- facility-row [store [fid _seed]]
  (let [rec (store/registered-facility store fid)
        sp (some-> rec :species facts/species-by-id)]
    [(code* fid)
     (esc (:name rec))
     (if sp (str (esc (:name sp)) " <span class=\"muted\">(" (esc (:id sp)) ")</span>")
         (str (pill "critical" "unresolved species id") " " (code* (:species rec))))
     (esc (:region rec))
     (str "<span class=\"num\">" (esc (:registered-head rec)) "</span>")
     (if rec (pill "ok" "registered") (pill "critical" "NOT registered"))]))

(defn- run-row [{:keys [label op facility-id phase disposition audit verdict]}]
  (let [reason (some :phase-reason audit)
        esc-reason (some #(when (= :approval-requested (:t %)) (:reason %)) audit)]
    [(code* label)
     (code* op)
     (code* facility-id)
     (code* phase)
     (disposition-pill disposition)
     (str "<span class=\"num\">" (esc (conf2 (:confidence verdict 0.0))) "</span>")
     (cond
       reason (code* reason)
       esc-reason (code* esc-reason)
       :else "<span class=\"muted\">—</span>")]))

(defn- gate-cell [phase op base]
  (let [{:keys [disposition reason]} (phase/gate phase {:op op} base)]
    (str (disposition-pill disposition)
         (when reason (str "<br><span class=\"muted\">" (code* reason) "</span>")))))

(defn- op-gate-row [op]
  (into [(code* op)
         (if (contains? governor/always-escalate-ops op)
           (pill "warn" "always escalates")
           (pill "muted" "no"))]
        (concat (map #(gate-cell % op :commit) (sort-by str (keys phase-labels)))
                [(gate-cell :phase-3 op :hold)])))

(defn- hard-rule-row [{:keys [rule times facilities detail]}]
  [(code* rule)
   (str "<span class=\"num\">" times "</span>")
   (str/join " " (map code* facilities))
   (esc detail)])

(defn- supply-row [by-cat [cid {:keys [name cost-threshold]}]]
  (let [obs (get by-cat cid)]
    [(code* cid)
     (esc name)
     (str "<span class=\"num\">" (esc cost-threshold) "</span>")
     (if obs (str "<span class=\"num\">" (esc (:cost obs)) "</span> @ " (code* (:facility obs)))
         "<span class=\"muted\">not ordered this run</span>")
     (if obs (disposition-pill (:disposition obs)) "<span class=\"muted\">—</span>")]))

(defn- concern-row [flagged [cid {:keys [name notifiable]}]]
  (let [obs (get flagged cid)]
    [(code* cid)
     (esc name)
     (if notifiable (pill "warn" "notifiable") (pill "muted" "not notifiable"))
     (if obs (disposition-pill (:disposition obs)) "<span class=\"muted\">not flagged this run</span>")]))

(defn- escalation-row
  "Derived per fact: the approver column asks THIS fact whether it carries
  an approver key, so a future change that starts recording one on some
  ops but not others shows up row by row instead of as a single sentence."
  [{:keys [op subject reason] :as fact}]
  (let [ks (approver-keys-in fact)]
    [(code* op)
     (code* subject)
     (code* reason)
     (if (seq ks)
       (str (pill "ok" "retained") " " (str/join " " (map code* ks)))
       (str (pill "warn" "no approver on the fact")
            " <span class=\"muted\">— the request was raised; no decision has been recorded</span>"))]))

(defn- record-row [{:keys [label record facility-id op]}]
  [(code* label)
   (code* op)
   (code* facility-id)
   (code* (:effect record))
   (code* (pr-str (:path record)))
   (let [ks (approver-keys-in record)]
     (if (seq ks)
       (str (pill "ok" "retained") " " (str/join " " (map code* ks)))
       (pill "warn" "no approver key in the record")))])

(defn- ledger-row [{:keys [t op subject facility-id basis disposition
                           confidence proposal-summary reason phase-reason]}]
  [(code* t)
   (code* (or op "—"))
   (code* (or subject facility-id "—"))
   (cond
     (seq basis) (str/join ", " (map esc basis))
     (or reason phase-reason) (code* (or phase-reason reason))
     disposition (code* disposition)
     :else "<span class=\"muted\">—</span>")
   (if confidence (str "<span class=\"num\">" (esc (conf2 confidence)) "</span>")
       "<span class=\"muted\">—</span>")
   (if proposal-summary (esc proposal-summary) "<span class=\"muted\">—</span>")])

;; ----------------------------- the page -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Reads nothing but that result and the actor's own namespaces."
  [{:keys [store runs] :as result}]
  (let [facts* (ledger runs)
        holds (hard-holds facts*)
        groups (hard-rule-groups facts*)
        probe (approver-probe result)
        by-cat (disposition-by-category runs)
        flagged (into {} (for [r runs
                               :when (= :flag-animal-health-concern (:op r))]
                           [(get-in r [:request :concern]) r]))
        committed-runs (filterv #(= :commit (:disposition %)) runs)
        escalations (:escalations probe)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-0142 &middot; equine facility operations coordinator</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Raising of horses and other equines (ISIC 0142) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · direct treatment and breeding/culling decisions are permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <p class=\"subtitle\">Build-time generated by <code>equineops.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from one real "
     "<code>equineops.operation</code> → <code>equineops.governor</code> → "
     "<code>equineops.phase</code> run over <code>equineops.store</code>. "
     "Every number below was produced by that run; nothing is hand-written.</p>\n"

     (section
      "Registered facilities (Store read-back)"
      (str "Read BACK out of the store after the run through "
           (code* "equineops.store/registered-facility") ", one call per seeded id, and joined "
           "against " (code* "equineops.facts/species-by-id") ". A facility that is not registered "
           "here cannot be the subject of ANY proposal — that is the governor's "
           (code* :facility-not-registered) " invariant, exercised further down.")
      ["Facility id" "Name" "Species" "Region" "Head on file" "Store"]
      (rows (map (partial facility-row store) seed-facilities)))

     (section
      (str "Operations this run performed (" (count runs) " runs)")
      (str "One row per actor invocation. " (code* "Disposition")
           " is what the pipeline actually returned; " (code* "Confidence")
           " is the advisor's own number as the governor received it (floor "
           "<span class=\"num\">" governor/confidence-floor "</span>); "
           (code* "Gate reason") " is the phase gate's or the escalation fact's own keyword. "
           "The last run in the HARD block is driven by a second actor whose advisor emits "
           (code* ":effect :execute") " — a real injected " (code* "equineops.advisor/Advisor")
           ", not a flag.")
      ["Run" "Op" "Facility" "Phase" "Disposition" "Confidence" "Gate reason"]
      (rows (map run-row runs)))

     (section
      "Action gate — what the phase gate actually answers"
      (str "Every cell is produced by CALLING " (code* "equineops.phase/gate")
           " with that phase, that op and a governor-clean " (code* :commit)
           " disposition. The right-most column feeds it a governor HARD "
           (code* :hold) " instead: no phase can turn a HARD violation back into a commit — "
           "the phase gate may only add caution, never remove it. "
           "The ops listed are " (code* "equineops.governor/known-ops")
           " — the closed allowlist, read from the var.")
      (into ["Op" "Always escalates?"]
            (concat (map #(str (esc %) "<br><span class=\"muted\">" (esc (get phase-labels %)) "</span>")
                         (sort-by str (keys phase-labels)))
                    ["Governor HARD → (phase 3)"]))
      (rows (map op-gate-row (sort-by str governor/known-ops))))

     (section
      (str "HARD governor holds fired by this run (" (count holds) " holds, "
           (count groups) " distinct rules)")
      (str "Grouped out of the run's real " (code* :governor-hold)
           " facts; the detail column is the governor's own string, verbatim. A HARD violation is "
           "un-overridable and never reaches a human at all — the build verifies that structurally "
           "(no " (code* :approval-requested) " in any held run's audit) rather than claiming it here. "
           "Note that " (code* "unregistered-facility-and-zero-count")
           " contributes to TWO rules from a single run: the governor accumulates violations rather "
           "than short-circuiting on the first.")
      ["Rule" "Times fired" "Facilities" "Detail (from the governor)"]
      (rows (map hard-rule-row groups)))

     (section
      "Supply cost thresholds — declared, then applied"
      (str "Left half is " (code* "equineops.facts/supply-categories") ". Right half is what this "
           "run's order for that category actually got. The boundary is inclusive ("
           (code* "registry/cost-exceeds-threshold?") " is " (code* ">") "), which is why the feed "
           "order at exactly 500 committed; tack's own 1000 threshold is why a 900 order that would "
           "have escalated under the default 500 did not.")
      ["Category" "Name" "Threshold" "Ordered this run" "Disposition"]
      (rows (map (partial supply-row by-cat) (sort-by key facts/supply-categories))))

     (section
      "Health-concern vocabulary — and why notifiability changes nothing"
      (str "Reference vocabulary from " (code* "equineops.facts/health-concerns")
           ", joined with the disposition each flagged concern actually received. "
           (code* :flag-animal-health-concern) " is in "
           (code* "equineops.governor/always-escalate-ops") ", so a notifiable concern and a "
           "non-notifiable one escalate identically — shown here as measurement rather than as a "
           "sentence. This actor has no authority to declare an outbreak, order treatment, or "
           "contact animal-health authorities.")
      ["Concern id" "Name" "Notifiable" "Disposition this run"]
      (rows (map (partial concern-row flagged) (sort-by key facts/health-concerns))))

     (section
      (str "Human approval queue (" (count escalations) " escalations)")
      (str "The " (code* :approval-requested) " facts this run raised. These are requests, not "
           "decisions: this repo's " (code* "equineops.operation")
           " has no resume path, so no run in this scenario has an approver yet — the approver "
           "column is DERIVED by probing each fact for "
           (str/join ", " (map code* approver-key-candidates))
           " rather than asserted.")
      ["Op" "Facility" "Escalation reason" "Approver on the fact"]
      (rows (map escalation-row escalations)))

     (section
      (str "What the commit path actually retained (" (count committed-runs) " commits)")
      (str "One row per commit record " (code* "equineops.operation/run-operation")
           " actually returned. <strong>Measured, not assumed:</strong> the "
           (code* "equineops.store/Store") " protocol exposes exactly "
           (str/join ", " (map code* (:store-protocol-fns probe)))
           " — there is no commit register to write a record into, so the record is handed back to "
           "the caller in memory and this actor never reads it again. Probing "
           "<span class=\"num\">" (:records-scanned probe) "</span> records, "
           "<span class=\"num\">" (:facts-scanned probe) "</span> audit facts and "
           "<span class=\"num\">" (:facility-records-scanned probe) "</span> store-held facility "
           "records for "
           (str/join ", " (map code* approver-key-candidates))
           " found "
           (if (:retained? probe)
             (str (pill "ok" "an approver key")
                  " — records: " (str/join " " (map code* (:approver-keys-in-records probe)))
                  ", facts: " (str/join " " (map code* (:approver-keys-in-facts probe)))
                  ", store: " (str/join " " (map code* (:approver-keys-in-store probe))))
             (str (pill "warn" "none") ". <strong>This is not the store dropping a "
                  "<code>:payload</code></strong> — the shape peers report; here the operation flow "
                  "never mints an approver in the first place, because there is no approval-resume "
                  "step to mint one. The escalation queue above is the audit-only join: it names the "
                  "op, the facility and the reason a human was asked <em>(audit only — no approver "
                  "is retained anywhere, because no approval has been recorded)</em>."))
           " The " (code* :actor) " field on a commit fact carries "
           (str/join " " (map code* (:commit-actor-ids probe)))
           ", which is the PROPOSING actor from the operator context ("
           (str/join " " (map code* (:context-actor-ids probe)))
           "), not an independent human approver — a distinction this section makes explicitly so "
           "the two are never conflated.")
      ["Run" "Op" "Facility" "Effect" "Store path" "Approver in the record?"]
      (rows (map record-row committed-runs)))

     (section
      (str "Audit ledger (this run — " (count facts*) " facts)")
      (str "Every fact every run produced, concatenated in order. There is no store-side ledger in "
           "this repo, so this is assembled from the runs' own " (code* :audit)
           " channels and nothing else.")
      ["Fact" "Op" "Facility" "Basis / reason" "Confidence" "Summary"]
      (rows (map ledger-row facts*)))

     "</main>\n"
     "<footer>\n"
     "  <p>Generated by <code>equineops.render-html</code> from a real "
     "<code>equineops.operation</code> → <code>equineops.governor</code> → "
     "<code>equineops.store</code> run. The build throws unless the run produces a real HARD "
     "governor hold across at least " min-distinct-hard-rules
     " distinct rules. Deterministic and timestamp-free: two consecutive builds are "
     "byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn assert-real-holds!
  "Invariants 1-6. Throws; never warns. A generator that renders a page
  with no HARD hold in it has not demonstrated the thing this repo exists
  to demonstrate, and a page whose HARD-rule section outlived the run that
  justified it is a lie with a table around it.

  Prints an evidence floor for every count it checks, so a run that
  scanned nothing cannot look like a run that found nothing wrong."
  [{:keys [runs] :as result}]
  (let [facts* (ledger runs)
        holds (hard-holds facts*)
        rules (into #{} (mapcat #(map :rule (:violations %)) holds))
        groups (hard-rule-groups facts*)
        committed (filterv #(= :commit (:disposition %)) runs)
        escalated (filterv #(= :escalate (:disposition %)) runs)]
    (println "RUNS\t" (count runs))
    (println "LEDGER-FACTS\t" (count facts*))
    (println "HARD-HOLDS\t" (count holds))
    (println "DISTINCT-HARD-RULES\t" (count rules))
    (println "COMMITS\t" (count committed))
    (println "ESCALATIONS\t" (count escalated))
    (when (zero? (count runs))
      (throw (ex-info "render-html: the scenario performed ZERO runs -- refusing to report on a run that never happened"
                      {})))
    (when (zero? (count holds))
      (throw (ex-info "render-html: the run produced ZERO :governor-hold facts carrying a violation -- refusing to write a console that shows no HARD hold"
                      {:ledger-facts (count facts*)})))
    (when (< (count rules) min-distinct-hard-rules)
      (throw (ex-info "render-html: fewer distinct HARD rules fired than the evidence floor requires"
                      {:fired (vec (sort-by str rules))
                       :count (count rules)
                       :floor min-distinct-hard-rules})))
    ;; invariant 3 -- a HARD hold must never have asked a human
    (doseq [r runs]
      (let [ts (into #{} (map :t (:audit r)))]
        (when (and (seq (hard-holds (:audit r))) (contains? ts :approval-requested))
          (throw (ex-info "render-html: a run that HARD-held ALSO escalated to a human approver"
                          {:run (:label r) :op (:op r) :facility (:facility-id r)})))))
    ;; invariant 4 -- no rendered HARD-rule row without a backing fact
    (doseq [{:keys [rule]} groups]
      (when-not (contains? rules rule)
        (throw (ex-info "render-html: HARD-rule row has no backing ledger fact"
                        {:rule rule}))))
    ;; invariant 5 -- the approver probe must have something to probe
    (when (zero? (count committed))
      (throw (ex-info "render-html: no op COMMITTED -- the approver-attribution probe would be vacuous"
                      {:runs (count runs)})))
    ;; invariant 6 -- the escalation queue must not be an empty table
    (when (zero? (count escalated))
      (throw (ex-info "render-html: no op ESCALATED -- the human-approval queue would render as a legitimate-looking empty table"
                      {:runs (count runs)})))
    (let [probe (approver-probe result)]
      (println "APPROVER-RETAINED\t" (:retained? probe))
      (println "STORE-PROTOCOL-FNS\t" (str/join " " (:store-protocol-fns probe))))
    {:holds (count holds) :rules (vec (sort-by str rules))
     :commits (count committed) :escalations (count escalated)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [holds rules commits escalations]} (assert-real-holds! result)
        html (render result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count (ledger (:runs result))) " ledger facts, "
                  holds " HARD holds over " (count rules) " distinct rules, "
                  commits " commits, " escalations " escalations, "
                  (count html) " chars)"))
    (println "HARD-RULES\t" (str/join " " (map str rules)))))
