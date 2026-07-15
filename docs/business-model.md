# Business Model: Equine-Facility Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0142`
- ISIC Rev. 4: `0142`
- Industry: Raising of horses and other equines
- Social impact: animal-welfare, rural-employment, working-animal-support

## Customer

- Small-to-medium breeding stables and studs
- Riding/training facilities (excluding racing/gambling operations)
- Draft/working-equine operations
- Donkey and mule raising operations

## Offer

- Herd management and record-keeping
- Veterinary appointment coordination
- Health and welfare tracking
- Supply procurement coordination (feed, veterinary supplies, tack)
- Audit trail and transparency

## Revenue

- SaaS subscription (per-head-per-month pricing)
- Supply chain integration fees
- API access for veterinary partners
- Data analytics and reporting add-ons

## Trust Controls

- No breeding or culling decisions without human sign-off
- No direct treatment administration
- All veterinary recommendations are proposals, not commands
- Facility/stable registration is required before any operation
- All animal health concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we NOT do

- **Veterinary treatment decisions** — the veterinarian decides treatment
- **Animal welfare decisions** — the stable operator decides welfare actions
- **Economic decisions** (breeding, culling) — remain human authority
- **Direct animal handling** — the robot manages records and logistics only
- **Racing or gambling activities** — out of scope entirely; this actor
  covers raising/breeding operations coordination only

## Supported Operations

### Herd Record Logging
- Daily herd counts
- Weight tracking
- Health status notes
- Birth/breeding records (logging only, not decision-making)

### Veterinary Coordination
- Schedule vet visits
- Track vet exam results
- Propose follow-up care (not order it directly)

### Health Concern Escalation
- Flag suspected disease (e.g. equine influenza, strangles)
- Report injuries or welfare concerns (e.g. colic, laminitis)
- Automatic escalation to stable operator/vet

### Supply Procurement
- Feed orders
- Veterinary supply orders
- Tack procurement
- Cost threshold escalation for large orders
