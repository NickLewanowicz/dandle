---
title: "Brief 06: Dandle Cloud honesty & signup hardening"
---

> **STATUS: Implemented (2026-11-01).** 321 unit tests green (+15); contact_email live on pockethost; QA verification of the on-device flow pending this round. Research: MetaMask/Binance seed-verification quiz flows; Signal/Proton/Bitwarden confirm-you-saved patterns; E2EE onboarding guidance (generated key + verification step is the industry standard, with a create-your-own escape hatch to protect onboarding).

# Brief 06 — Dandle Cloud honesty & signup hardening

## Goal
Tell users the truth about the cloud service, make them contactable before any service change, and close the weakest link in the E2E chain: human-chosen passphrases.

## Decisions

1. **Keep the name "Dandle Cloud."** Add an honest service notice on the cloud entry screen (muted, bodySmall, under the lead line):
   > "Dandle Cloud is a free service and could change or end. Your data stays yours — it's encrypted with your passphrase and readable even without us."
2. **Contactability: optional email, decoupled from login.**
   - New `contact_email` (plain text, optional, unverified) on the `users` collection — NOT PocketBase's auth `email` (no reset promises, no identity coupling, no break for existing accounts). Username-as-email rejected: it couples login identity to contactability.
   - Field on Create account: "Email (optional)" + supporting text "Only used to warn you before big service changes. Never marketing." Sent at createUser; stored in clear (it is contact metadata by definition — disclosed in the copy).
3. **Create-account form layout:** Password + Confirm password side-by-side (one Row, two half-width fields); encryption passphrase moves to its own visually separated section below (small section label "Encryption passphrase").
4. **Generated passphrase + paste-to-verify (replaces the confirm-passphrase field and the "I've saved it" dialog):**
   - Passphrase field arrives PRE-FILLED with a generated passphrase (≥ 60 bits: engineer's choice of generator — 5–6 words from a compact built-in wordlist, or equivalent random; word-style preferred for transcribability), with Regenerate and "Create my own" (switches to an empty editable field) affordances.
   - Copy above it: "This unlocks your data. Save it somewhere safe — a password manager is ideal."
   - Continue → **verification step** (same screen, step 2 of 2): "Paste your passphrase to confirm you've saved it." Single field; must match exactly; then the account is created. (This is the MetaMask-style proof-of-backup, softened per research: paste-from-saved-place, not memory retype.)
   - The old non-dismissable "Save your passphrase somewhere safe." dialog is REMOVED — the two-step flow replaces it. Sign-in tab is unchanged (fields: username/password/passphrase).
   - Phishing-safety copy note on the verify step: keep the app context visible ("Create your account · step 2 of 2") — seed-phrase confirmation UX is a known phishing mimic pattern.
5. **Docs:** flow 12 + checklist 14 updated; tags `login/cloud_passphrase_generate`, `login/cloud_passphrase_regen`, `login/cloud_passphrase_verify`, `login/contact_email`; schema script adds `contact_email`.

## Amendment (2026-11-01, owner + research)
The original §3 ("Password + Confirm password side-by-side") is REVERSED after research: the
adjacent confirm field is the obsolete pattern — NIST 800-63B endorses the show-password toggle
over re-entry; web.dev, Nielsen Norman, and 2025 guides (Authgear, UX Movement) document that
confirm fields double errors while validating nothing (managers autofill both identically).
SHIPPED: single Password field with Show/Hide; the only next-step confirmation is the
passphrase paste-verify — reserved for the irrecoverable secret, where separate-step
confirmation IS the standard (wallet seed-phrase practice).

## Non-goals
Email verification/SMTP; passphrase recovery flows; changing sign-in; marketing use of the email (copy explicitly excludes it).
