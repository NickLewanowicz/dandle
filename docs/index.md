---
title: An offline-first Android client for Baby Buddy
hide:
  - navigation
  - toc
---

# An offline-first Android client for Baby Buddy

A modern, offline-first Android client for [Baby Buddy](https://github.com/babybuddy/babybuddy),
the self-hosted baby tracker — rebuilt with Kotlin, Jetpack Compose, and Material 3.
The Room database is the app's source of truth; server sync is a background concern
that can fail quietly and catch up later.

![The new app's dashboard — six quick actions, live timers, and the timeline](screenshots/new-app/flows/99-final-dashboard.png)

## Why a rewrite

Three gaps in the original app drive this one:

- **Offline queue** — creates and edits work with no network, queue locally, and surface
  conflicts in-app with an edit-or-delete resolution path.
- **In-app time editing** — timers and start/end times are correctable inline, midnight
  crossings included, instead of pushing you to the web UI.
- **Home-screen widgets** — glanceable quick actions and running timers via Glance, so
  logging a feed never requires opening the app.

## Try it

The fastest end-to-end tour is the [QA runbook](qa/RUNBOOK.md): a seeded local server, an
emulator, and tap-by-tag steps for every flow. The
[functional spec](flows/new-app/00-functional-spec.md) is the canonical contract — every
user path, feature, state, and flow must be documented there before it counts as done.

## Explore the docs

<div class="grid cards" markdown>

- :material-file-document-edit-outline:{ .lg .middle } **Functional spec**

    The rewrite contract — every user path, feature, and flow, in one document.

    [:octicons-arrow-right-24: Read the spec](flows/new-app/00-functional-spec.md)

- :material-gesture-tap-button:{ .lg .middle } **Quick actions**

    The six pinned logging flows: Feeding, Diaper, Sleep, Notes, Pumping, Tummy time.

    [:octicons-arrow-right-24: Flow spec](flows/new-app/20-quick-actions.md)

- :material-wifi-off:{ .lg .middle } **Offline & sync**

    How edits queue offline and how conflicts surface with a resolution path.

    [:octicons-arrow-right-24: Flow spec](flows/new-app/40-offline-sync.md)

- :material-widgets:{ .lg .middle } **Widgets**

    Glance quick actions and timer widgets, with placement and refresh behavior.

    [:octicons-arrow-right-24: Flow spec](flows/new-app/50-widgets.md)

- :material-clipboard-check-outline:{ .lg .middle } **QA runbook**

    Operationalized device testing: scripts, testTags, server log tails, screenshots.

    [:octicons-arrow-right-24: Run the app](qa/RUNBOOK.md)

- :material-palette-outline:{ .lg .middle } **Icon design**

    How the app's own icon set was designed — original visuals, no copied assets.

    [:octicons-arrow-right-24: Design notes](design/icons.md)

</div>
