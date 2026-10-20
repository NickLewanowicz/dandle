---
title: Build docs
---

# Build docs — how this is built

This section is the workshop: the engineering contract, the QA process, the
research, and the full history of how the app came to be — including the parts
that only an openly AI-built project would publish.

**Looking for the app itself?** Start at the [Guide](guide/logging.md) — that's
written for parents. Everything here is for contributors, auditors, and the
curious.

## Where to start

| If you want to… | Go to |
|---|---|
| Know exactly what the app must do (the contract every change is checked against) | [Functional spec](flows/new-app/00-functional-spec.md) |
| See how correctness is verified (emulator flows, canaries, regression gate) | [QA runbook](qa/RUNBOOK.md) and [QA findings](flows/new-app/99-qa-findings.md) |
| Check an API behavior against the real server | [Baby Buddy API research](research/babybuddy-api.md) |
| Compare against the original Android app | [Original app reference](flows/existing-app/00-overview.md) |
| Understand how an AI-driven team builds this | [Personas](PERSONAS.md) and the [architecture brief](briefs/00-architecture.md) |

## The short version of the process

A human product owner sets scope and owns every decision. AI agents implement
and test under narrow mandates. No UI change ships without the regression gate
(`scripts/qa/capture-flows.sh --assert`) re-capturing the screenshot evidence,
and no behavior change lands without a JVM regression test. When the gate finds
a bug — including crashes — it's recorded in [QA
findings](flows/new-app/99-qa-findings.md) with its fix and evidence, not
quietly patched.
