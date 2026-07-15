# Contributing

**Maturity: `:implemented`** — `src/equineops/` implements the reference
EquineOpsAdvisor / Equine Facility Operations Governor actor as a
synchronous stub (langgraph-clj StateGraph wiring deferred, see
`operation.cljc`). Contributions that extend coverage are welcome:
langgraph-clj StateGraph integration (real `interrupt-before`/checkpoint-based
human-in-the-loop resume for escalated operations), a Datomic/kotoba-server
`Store` backend, a real LLM `Advisor` implementation, additional Governor
rules, and species/health-concern reference-data expansion in
`equineops.facts`. Open an issue or PR. License: AGPL-3.0-or-later.
