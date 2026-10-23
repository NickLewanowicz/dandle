// =============================================================================
// Sidebar for the Dandle docs site.
//
// Explicit items, hand-ordered to mirror the MkDocs nav that the persona wave
// reviewed (2026-10-20): a small PARENT surface first, then ALL contributor +
// AI-agent machinery under one clearly-labeled "Build docs" section. Content
// is not deleted — it is housed.
//
// Labels are explicit so section names stay stable even when a page's H1 or
// frontmatter title is internal jargon.
// =============================================================================

/** @type {import('@docusaurus/plugin-content-docs').SidebarItems} */
const sidebars = {
  site: [
    // ---- Parent surface -----------------------------------------------------
    { type: 'doc', id: 'overview/quick-start', label: 'Quick Start' },
    {
      type: 'category',
      label: 'Guide',
      items: [
        { type: 'doc', id: 'guide/logging', label: 'Logging a feed, diaper, or nap' },
        { type: 'doc', id: 'guide/timers-and-editing', label: 'Timers, and fixing a time' },
        { type: 'doc', id: 'guide/offline', label: 'No signal? Keep logging' },
        { type: 'doc', id: 'guide/widgets', label: 'Widgets' },
        { type: 'doc', id: 'guide/data-and-privacy', label: 'Your data & security' },
      ],
    },
    { type: 'doc', id: 'get-the-app', label: 'Get the app' },
    { type: 'doc', id: 'about', label: 'About' },

    // ---- Build docs (the workshop) ------------------------------------------
    {
      type: 'category',
      label: 'Build docs',
      items: [
        { type: 'doc', id: 'build-docs', label: 'Build docs home' },

        {
          type: 'category',
          label: 'The rewrite contract',
          items: [
            { type: 'doc', id: 'flows/new-app/00-functional-spec', label: 'Functional spec' },
            { type: 'doc', id: 'flows/new-app/01-ux-spec', label: 'UI shell spec' },
          ],
        },

        {
          type: 'category',
          label: 'Flow verification (QA)',
          items: [
            { type: 'doc', id: 'flows/new-app/10-login', label: 'Login' },
            { type: 'doc', id: 'flows/new-app/20-quick-actions', label: 'Quick actions' },
            { type: 'doc', id: 'flows/new-app/30-timeline-and-edit', label: 'Timeline & quick edit' },
            { type: 'doc', id: 'flows/new-app/40-offline-sync', label: 'Offline & sync' },
            { type: 'doc', id: 'flows/new-app/50-widgets', label: 'Widgets' },
            { type: 'doc', id: 'flows/new-app/60-help-about', label: 'Help & about' },
            { type: 'doc', id: 'flows/new-app/widgets', label: 'Widget QA reference' },
          ],
        },

        {
          type: 'category',
          label: 'Design & history',
          items: [
            { type: 'doc', id: 'flows/new-app/90-owner-ui-rework', label: 'Owner UI rework' },
            { type: 'doc', id: 'flows/new-app/70-widget-ux-review', label: 'Widget UX review' },
            { type: 'doc', id: 'flows/new-app/02-user-persona-review', label: 'User persona review' },
            { type: 'doc', id: 'design/icons', label: 'Icon design' },
            {
              type: 'category',
              label: 'Persona feedback (round 1)',
              items: [
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/00-plan', label: 'Plan' },
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/00-triage', label: 'Triage' },
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/elena', label: 'Elena' },
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/jordan', label: 'Jordan' },
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/priya', label: 'Priya' },
                { type: 'doc', id: 'flows/new-app/80-persona-feedback/sam', label: 'Sam' },
              ],
            },
          ],
        },

        {
          type: 'category',
          label: 'QA',
          items: [
            { type: 'doc', id: 'qa/RUNBOOK', label: 'QA runbook' },
            { type: 'doc', id: 'qa/FEATURE-CHECKLISTS', label: 'Feature checklists' },
            { type: 'doc', id: 'qa/README', label: 'QA overview' },
            { type: 'doc', id: 'flows/new-app/99-qa-findings', label: 'QA findings' },
          ],
        },

        {
          type: 'category',
          label: 'Research',
          items: [
            { type: 'doc', id: 'research/babybuddy-api', label: 'Baby Buddy API' },
            { type: 'doc', id: 'research/existing-app-architecture', label: 'Existing app architecture' },
          ],
        },

        {
          type: 'category',
          label: 'Original app reference',
          items: [
            { type: 'doc', id: 'flows/existing-app/00-overview', label: 'Overview' },
            { type: 'doc', id: 'flows/existing-app/01-login', label: 'Login' },
            { type: 'doc', id: 'flows/existing-app/02-dashboard', label: 'Dashboard' },
            { type: 'doc', id: 'flows/existing-app/03-sleep', label: 'Sleep' },
            { type: 'doc', id: 'flows/existing-app/04-feeding', label: 'Feeding' },
            { type: 'doc', id: 'flows/existing-app/05-diaper', label: 'Diaper' },
            { type: 'doc', id: 'flows/existing-app/06-note', label: 'Notes' },
            { type: 'doc', id: 'flows/existing-app/07-pumping', label: 'Pumping' },
            { type: 'doc', id: 'flows/existing-app/08-tummytime', label: 'Tummy time' },
            { type: 'doc', id: 'flows/existing-app/09-history', label: 'History' },
            { type: 'doc', id: 'flows/existing-app/10-childswitch', label: 'Child switching' },
            { type: 'doc', id: 'flows/existing-app/11-settings', label: 'Settings' },
            { type: 'doc', id: 'flows/existing-app/12-edge', label: 'Edge cases' },
            { type: 'doc', id: 'flows/existing-app/99-discrepancies', label: 'Discrepancies' },
          ],
        },

        {
          type: 'category',
          label: 'Project',
          items: [
            { type: 'doc', id: 'PERSONAS', label: 'Personas' },
            {
              type: 'category',
              label: 'Briefs',
              items: [
                { type: 'doc', id: 'briefs/00-architecture', label: 'Architecture' },
                { type: 'doc', id: 'briefs/01-login-and-data-layer', label: 'Login & data layer' },
                { type: 'doc', id: 'briefs/02-stages-2-to-5', label: 'Stages 2–5' },
                { type: 'doc', id: 'briefs/03-icons-and-theming', label: 'Icons & theming' },
                { type: 'doc', id: 'briefs/independent-review', label: 'Independent framework review' },
                {
                  type: 'category',
                  label: 'Framework evaluation',
                  items: [
                    { type: 'doc', id: 'briefs/framework-eval/DECISION', label: 'Decision' },
                    { type: 'doc', id: 'briefs/framework-eval/kotlin-compose', label: 'Kotlin + Compose' },
                    { type: 'doc', id: 'briefs/framework-eval/react-native', label: 'React Native' },
                    { type: 'doc', id: 'briefs/framework-eval/flutter', label: 'Flutter' },
                  ],
                },
              ],
            },
          ],
        },
      ],
    },
  ],
};

module.exports = sidebars;
