# ADR 0003: This is a demo app — pedagogical framing

- Status: Accepted
- Date: 2026-05-15
- Deciders: ze

## Context

The `insurance-app` is not a product. Its purpose is to be a **teaching artifact** for a self-paced learning track on Open Liberty + WSO2 (MI / IS / APIM) + Kafka + Redis + SigNoz, aimed at engineers learning enterprise Java backend patterns end-to-end. Real insurance applications operate under regulatory regimes (filings, capital adequacy, claims-handling rules) that we are explicitly out of scope for; the only thing this app shares with one is the entity names.

This framing changes how we treat the code and the UI:

- Code-style obligations shift toward *demonstrating canonical patterns* even when a tighter, less didactic version would work.
- The UI must communicate what is being learned, not just what is being clicked.
- Maintenance includes keeping the teaching content honest with respect to what the code actually does.

## Decision

The app embraces its pedagogical purpose explicitly:

1. **Every UI page carries a "Platform capability" callout** — a sidebar / info-box / footer block that names which platform capability is being demonstrated on that page, with one or two sentences describing how (e.g. "This page hits a Redis read-through cache with a 15-minute TTL; check `PolicyService.getByNumber` for the pattern").

2. **A dedicated `/tour` page** maps every feature in the app to the enterprise capabilities it teaches — Kafka topics involved, Redis keys touched, MI mediations called, APIM products it sits behind, observability spans it emits. The `/tour` page is the table of contents for the curriculum and the canonical place a student starts.

3. **Code comments explain pattern intent, not what the code does.** "What" comments are still discouraged.

4. **The README explicitly says** the app is a demo, names the curriculum track it backs, and disclaims any production fitness.

5. **No real customer data ever.** Seeds, fixtures, integration tests — synthetic.

## Consequences

Positive:
- Students who navigate the running app *and* read the code see the same lesson, reinforced.
- The `/tour` page is a hub: the curriculum links into it, and the app links out to the curriculum modules.
- Demonstrating a pattern at canonical depth becomes a design constraint rather than an afterthought.

Negative / accepted trade-offs:
- Pages are slightly more verbose than a production app's would be (callout boxes).
- Drift risk: when a capability is refactored, the page callouts and `/tour` entries must be updated alongside the code.

## Alternatives considered

- **Look-and-feel like production, with teaching content elsewhere (the blog).** Rejected: when the code, the UI, and the curriculum live in three places, students miss two of them.
- **Inline code comments only, no UI callouts.** Rejected: half the students will navigate the running app before opening the IDE.

## Revisit when

- This app stops being a teaching artifact (e.g., spun off as a starter template). At that point the callouts come out and the `/tour` page moves to a docs site.
- A second domain variant is added (e.g. health insurance); the `/tour` page architecture has to scale to multi-variant.
