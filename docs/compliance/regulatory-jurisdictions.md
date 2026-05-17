# Regulatory jurisdictions — insurance-app gap analysis

This document is the regulator-by-regulator index a compliance officer would walk before signing off on a production deployment of `insurance-app`. It is deliberately scoped to the **digital-first** auto/motor-insurance product the app actually implements (quote → bind → pay → claim), not the broader set of regulations any insurance carrier would face.

Each section identifies (a) the regulator and remit, (b) the 2–3 requirements that bite a digital-first insurer hardest, (c) the gap in the current codebase, (d) severity for go-live in that jurisdiction, (e) an owner placeholder.

**Severity legend:**
- **Blocker** — go-live in this jurisdiction is illegal or invites enforcement action until the gap is closed.
- **Moderate** — go-live is technically possible but invites material regulatory or reputational risk.
- **Minor** — a documented procedural gap that can be closed in the first quarter post-launch.

The teaching artifact today sits behind no regulator anywhere — these are the gaps that would open the moment a real customer's data was processed. Cross-reference: [`docs/qa-roadmap.md`](../qa-roadmap.md), [`docs/security-baseline.md`](../security-baseline.md), [`docs/compliance/pen-test-vendor-prep.md`](./pen-test-vendor-prep.md), [`docs/compliance/pii-data-flow.md`](./pii-data-flow.md).

---

## Bangladesh — IDRA (primary jurisdiction)

**Regulator:** Insurance Development and Regulatory Authority of Bangladesh (IDRA), established under the Insurance Act 2010 and the IDRA Act 2010.

**Load-bearing requirements for a digital insurer:**
1. **Product approval before sale** — every retail insurance product (including the motor product the app sells) must be filed with and approved by IDRA before it is offered. Premium tables, terms, and exclusions go in the filing.
2. **Bangla-language disclosures** — the Insurance Act requires policy documents and key disclosures to be available in Bangla; the customer must be able to read what they are buying.
3. **Bangladesh Bank circulars on digital payment** — the payment leg is supervised by Bangladesh Bank (not IDRA), and any settlement to a local merchant account requires PSO/PSP licensing of the gateway.

**Current gap:**
- No product-filing artifact (premium-rate card, terms-and-conditions PDF) exists in the repo.
- Customer portal is English-only; no `lang=bn` translations under `gui/customer-app/`.
- Payment path is a WireMock stub; no licensed Bangladesh PSP is integrated.

**Severity:** Blocker
**Owner:** @TBD

---

## United States — state-by-state (NAIC model + each state DOI)

**Regulator:** insurance is regulated **at the state level** in the US — there are 50 + DC + territories Departments of Insurance. The National Association of Insurance Commissioners (NAIC) publishes model laws that most states adopt with variations. A digital carrier must license in every state it sells in.

**Load-bearing requirements for a digital insurer:**
1. **NAIC Insurance Data Security Model Law (#668)** — adopted by ~25 states. Requires a written information security program, annual board reporting, and incident notification to the commissioner within **72 hours** of a cybersecurity event affecting >250 consumers.
2. **State-specific privacy laws stacking on top of HIPAA-style insurance carve-outs** — the California Consumer Privacy Act / CPRA, New York DFS Cybersecurity Reg (23 NYCRR Part 500), Colorado Privacy Act, Virginia CDPA, and so on. CPRA gives consumers right-to-delete, right-to-know, right-to-correct.
3. **Rate filing + actuarial-justification rules** — the premium calculation in `QuoteService` would need to be filed in every state and be defensible against discriminatory-impact challenges (age-banded pricing is allowed in most states for auto; some prohibit gender or credit-score factors).

**Current gap:**
- No incident-response runbook with a 72-hour notification clock; nothing under `docs/runbooks/` references regulatory notification.
- No CPRA-style right-to-delete API (see [`pii-data-flow.md` §4](./pii-data-flow.md)).
- Pricing model in `QuoteService` is a hardcoded formula on `driver_age` + `coverage_type` with no actuarial documentation or bias audit.

**Severity:** Blocker (per-state; you cannot lawfully sell auto insurance to a US resident without a license in their state and a filed rate)
**Owner:** @TBD

---

## United Kingdom — FCA + PRA

**Regulator:** Financial Conduct Authority (conduct, consumer protection, market integrity) and Prudential Regulation Authority (solvency, capital). FCA is the one a digital-front-end product interacts with daily.

**Load-bearing requirements for a digital insurer:**
1. **Consumer Duty (PS22/9, in force since July 2023)** — firms must deliver good outcomes across products, price-and-value, consumer understanding, and consumer support. Each is testable: can the customer understand what they bought? Is there fair price-vs-value? Can they cancel as easily as they bought?
2. **ICOBS (Insurance Conduct of Business Sourcebook)** — prescribes pre-contract disclosures (the IPID — Insurance Product Information Document), suitability, demands-and-needs statement, and a **14-day cancellation right** for general insurance.
3. **Operational Resilience (PS21/3, in force since March 2025)** — every important business service must have a documented impact tolerance, mapped dependencies, and severe-but-plausible-scenario testing.

**Current gap:**
- No IPID artifact; the quote screen jumps straight to a premium number with no standardized disclosure.
- No demands-and-needs capture; no cooling-off / 14-day cancellation API on the policy resource.
- Operational-resilience mapping doesn't exist; the closest artifact is [`docs/runbooks/`](../runbooks/) which is incident-shaped, not impact-tolerance-shaped.

**Severity:** Blocker
**Owner:** @TBD

---

## European Union — EIOPA + national supervisors

**Regulator:** European Insurance and Occupational Pensions Authority (EIOPA) sets pan-EU standards; each member state's supervisor (BaFin in Germany, ACPR in France, etc.) is the day-to-day authority. A digital insurer "passports" via the Insurance Distribution Directive (IDD).

**Load-bearing requirements for a digital insurer:**
1. **Insurance Distribution Directive (IDD, 2016/97)** — the EU's IPID requirement (mirrored in UK ICOBS), product oversight and governance (POG), and the demands-and-needs test.
2. **GDPR (2016/679)** — lawful basis for processing, data-subject rights (access, rectification, erasure, portability, objection), 72-hour breach notification, DPO designation if processing is at scale, DPIA before high-risk processing (claim photos + OCR almost certainly qualify).
3. **DORA (Digital Operational Resilience Act, in force January 2025)** — ICT risk management framework, third-party-provider register, incident classification + reporting, threat-led penetration testing on a triennial cadence.

**Current gap:**
- No DPIA artifact for the claim-OCR pipeline (binary photo + OCR text are GDPR Article 9-adjacent if injury/medical info is captured).
- No data-subject-rights endpoints (see [`pii-data-flow.md` §4](./pii-data-flow.md)).
- DORA: no ICT third-party register; partial pen-test prep exists ([`pen-test-vendor-prep.md`](./pen-test-vendor-prep.md)) but not on DORA's triennial-TLPT cadence.

**Severity:** Blocker
**Owner:** @TBD

---

## Canada — OSFI + provincial regulators

**Regulator:** Office of the Superintendent of Financial Institutions (OSFI) federally; auto insurance is provincially regulated (FSRA in Ontario, AMF in Quebec, government insurers in BC/SK/MB).

**Load-bearing requirements for a digital insurer:**
1. **OSFI Guideline B-13 (Technology and Cyber Risk Management, in force 2024)** — board-approved technology risk appetite, third-party risk management aligned with B-10, and incident reporting to OSFI within **24 hours** of a material technology or cyber incident.
2. **PIPEDA + provincial privacy laws (PIPA BC/AB, Quebec Law 25)** — Law 25 in particular requires a privacy officer, mandatory privacy impact assessments, and breach notification.
3. **Provincial auto-insurance rate boards** — Ontario's FSRA, Alberta's AIRB, etc., approve rates before they may be charged.

**Current gap:**
- Same shape as US: no incident-response runbook with a regulatory clock, no privacy-officer designation, no actuarial documentation of the rate model.
- Quebec Law 25 specifically: French-language requirement under Bill 96 stacks on top — `gui/customer-app/` has no `lang=fr` bundle.

**Severity:** Blocker
**Owner:** @TBD

---

## India — IRDAI

**Regulator:** Insurance Regulatory and Development Authority of India.

**Load-bearing requirements for a digital insurer:**
1. **IRDAI (Maintenance of Insurance Records) Regulations 2015** — all policyholder records must be **held in data centers located in India**. This is a hard data-localization rule, not a "preferred."
2. **Product filing under "Use and File" / "File and Use"** — every retail product must be registered with IRDAI before sale; bundled disclosures (the proposal form, key features document) prescribed by regulation.
3. **Digital Personal Data Protection Act 2023 (DPDP)** — consent must be freely given, specific, informed, unconditional, and unambiguous; a Consent Manager intermediary is contemplated. Sensitive personal data is broadly defined and includes financial info.

**Current gap:**
- Data resides on a VM in the user's home lab (Bangladesh). Cannot serve Indian policyholders without an Indian-region deployment.
- No consent-capture flow at quote time; the customer-app jumps from form submission to bind with no recorded consent receipt.

**Severity:** Blocker
**Owner:** @TBD

---

## Singapore — MAS

**Regulator:** Monetary Authority of Singapore (MAS) supervises all financial services including insurance under the Insurance Act 1966.

**Load-bearing requirements for a digital insurer:**
1. **MAS Notice 127 (Technology Risk Management)** + **TRM Guidelines** — outsourcing notification, system availability of 99.95% for critical systems, RTO ≤ 4 hours.
2. **PDPA (Personal Data Protection Act)** — consent, purpose limitation, access and correction obligations, mandatory data-breach notification within **3 calendar days** of assessment.
3. **MAS Notice on Cyber Hygiene** — six baseline controls including admin-account inventory, security patches, and multi-factor authentication for all administrative accounts.

**Current gap:**
- MFA is not enforced on the agent dashboard (single-factor password against WSO2 IS today).
- System availability is best-effort single-VM; no documented RTO/RPO.

**Severity:** Moderate (gaps closable in weeks for a small-scale launch; blocker only at scale)
**Owner:** @TBD

---

## Australia — APRA + ASIC

**Regulator:** APRA prudentially supervises; ASIC regulates conduct, disclosure, and licensing of financial services (insurance distribution requires an Australian Financial Services Licence — AFSL).

**Load-bearing requirements for a digital insurer:**
1. **CPS 234 (Information Security)** — board-approved info-security capability, classification of information assets, **72-hour notification** to APRA of material info-security incidents.
2. **Design and Distribution Obligations (DDO, in force 2021)** — every retail product needs a Target Market Determination (TMD); distribution must be consistent with the TMD.
3. **Privacy Act 1988 + Australian Privacy Principles (APPs)** — APP 11 (security of personal information), APP 8 (cross-border disclosure — accountability stays with the original collector).

**Current gap:**
- No TMD artifact; nothing in the repo describes the intended customer cohort.
- Cross-border: data on a Bangladesh VM is a notifiable cross-border disclosure under APP 8.

**Severity:** Blocker
**Owner:** @TBD

---

## Cross-jurisdictional themes

Three patterns repeat across every jurisdiction above and warrant a single project-wide owner:

1. **Data residency vs the single-VM lab** — Bangladesh, India, EU, and Australia all impose locality/cross-border-transfer rules that the current single-VM lab violates by default. The right architectural answer is per-region deployments with data-residency assertions in the OpenAPI spec. See [`pii-data-flow.md` §5](./pii-data-flow.md).
2. **Right-to-delete / data-subject-rights API** — GDPR, CPRA, PIPEDA, PDPA, DPDP, and Law 25 all require some form of access + erasure path. Build it once. Filed as a follow-up under issue #31 ([`pii-data-flow.md` §4](./pii-data-flow.md)).
3. **Regulatory incident-notification clock** — clocks range from 24h (Canada OSFI) to 72h (NAIC, GDPR, APRA CPS 234) to 3 days (Singapore PDPA). The Phase 6 incident runbook needs an explicit "regulator notification" step with a per-jurisdiction lookup table.

## Status

This document is an **index** and gap analysis, not a remediation plan. Closing the gaps requires legal-counsel input and is out of scope for the engineering team alone. The next step is to share this document with the hypothetical compliance officer before any jurisdiction-specific go-live decision.
