// @ts-check
// =============================================================================
// babybuddy-next documentation site — Docusaurus 3 (the stack Immich uses).
//
// The canonical content stays in ../docs (the spec is the product of record);
// this site only renders it: the docs plugin mounts the repo's docs/ directory
// at routeBasePath '/'. Nothing under ../docs moves because of the site.
//
// Local preview (from website/):
//   npm ci
//   npm start          # dev server with live reload
//   npm run build      # static site -> website/build
//   npm run serve      # serve the built site
//
// CI: .github/workflows/deploy-docs.yml builds and publishes to GitHub Pages.
// Cloudflare Pages alternative (no workflow file needed):
//   Build command:     cd website && npm ci && npm run build
//   Build output dir:  website/build
// =============================================================================

/** @type {import('@docusaurus/types').Config} */
const config = {
  title: 'Baby Buddy Next',
  tagline:
    'An offline-first Android client for Baby Buddy — log feeds, diapers, sleep, and more, even with no signal.',
  // Placeholder until the public home is chosen; enables canonical URLs and
  // social previews. Adjust when the repo goes public.
  url: 'https://babybuddy-next.pages.dev',
  baseUrl: '/',
  favicon: 'img/favicon.png',

  // GitHub Pages deploy plumbing (used by the deploy-docs workflow).
  organizationName: 'babybuddy',
  projectName: 'babybuddy-next',

  // Strict like MkDocs --strict was: a broken link fails the build.
  onBrokenLinks: 'throw',

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      /** @type {import('@docusaurus/preset-classic').Options} */
      ({
        docs: {
          // The repo's docs/ directory IS the site content. Keep it canonical.
          path: '../docs',
          routeBasePath: '/',
          sidebarPath: require.resolve('./sidebars.js'),
          // MkDocs rendered every section expanded; keep that scannability.
          sidebarCollapsed: false,
          // Keep doc ids/routes mirroring the file names exactly
          // (`flows/new-app/50-widgets` stays `50-widgets`): the sidebar and
          // the repo structure then agree 1:1, and `50-widgets.md` vs
          // `widgets.md` don't collide into the same stripped id.
          numberPrefixParser: false,
          exclude: [
            // Design working files (icon-iteration HTML sheets), never linked
            // from any doc — kept out of the published site, same as MkDocs did.
            '**/candidates/icon-round2/**',
          ],
        },
        blog: false,
        theme: {
          customCss: require.resolve('./src/css/custom.css'),
        },
      }),
    ],
  ],

  themes: [
    // Mermaid (the architecture brief's queue-state + data-flow diagrams).
    '@docusaurus/theme-mermaid',
    // Offline full-text search — no Algolia account needed.
    [
      require.resolve('@easyops-cn/docusaurus-search-local'),
      /** @type {import('@easyops-cn/docusaurus-search-local').PluginOptions} */
      ({
        hashed: true,
        indexDocs: true,
        indexBlog: false,
        indexPages: false,
        docsRouteBasePath: '/',
        // Same directory the docs plugin mounts — keeps indexing explicit and
        // silences the plugin's default "docsDir doesn't exist" warning.
        docsDir: '../docs',
        language: ['en'],
        highlightSearchTermsOnTargetPage: true,
      }),
    ],
  ],

  markdown: {
    mermaid: true,
    hooks: {
      onBrokenMarkdownLinks: 'warn',
    },
  },

  themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
    ({
      // Dark-first, like the app: default to dark, follow the OS preference on
      // first visit, and always let the visitor toggle.
      colorMode: {
        defaultMode: 'dark',
        disableSwitch: false,
        respectPrefersColorScheme: true,
      },

      announcementBar: {
        id: 'beta_open_build',
        content:
          '⚡ Beta — built openly by a human + AI agent team. <a href="/build-docs">Read how it\'s built →</a>',
        backgroundColor: '#2e3a8c',
        textColor: '#ffffff',
        isCloseable: true,
      },

      navbar: {
        title: 'Baby Buddy Next',
        logo: {
          alt: 'Baby Buddy Next',
          src: 'img/favicon.png',
        },
        items: [
          { to: '/', label: 'Home', position: 'left' },
          { to: '/overview/quick-start', label: 'Quick Start', position: 'left' },
          { to: '/guide/logging', label: 'Guide', position: 'left' },
          { to: '/build-docs', label: 'Build docs', position: 'left' },
          {
            href: 'https://github.com/babybuddy/babybuddy-next',
            position: 'right',
            className: 'header-github-link',
            'aria-label': 'GitHub repository',
          },
        ],
      },

      footer: {
        style: 'dark',
        links: [
          {
            title: 'For parents',
            items: [
              { to: '/overview/quick-start', label: 'Quick Start' },
              { to: '/get-the-app', label: 'Get the app' },
              { to: '/guide/logging', label: 'Guide' },
              { to: '/guide/offline', label: 'Offline' },
              { to: '/guide/data-and-privacy', label: 'Your data & security' },
              { to: '/about', label: 'About' },
            ],
          },
          {
            title: 'For contributors',
            items: [
              { to: '/build-docs', label: 'Build docs home' },
              { to: '/flows/new-app/00-functional-spec', label: 'Functional spec' },
              { to: '/qa/RUNBOOK', label: 'QA runbook' },
              { to: '/flows/new-app/99-qa-findings', label: 'QA findings' },
              { to: '/PERSONAS', label: 'Personas' },
              { to: '/research/babybuddy-api', label: 'Baby Buddy API' },
            ],
          },
        ],
        copyright: `Built openly, one hand at a time. Upstream credit: <a href="https://github.com/babybuddy/babybuddy" target="_blank" rel="noopener noreferrer">Baby Buddy</a> (BSD-2-Clause) — the server this independent client connects to; <a href="https://github.com/babybuddy/babybuddy-for-android" target="_blank" rel="noopener noreferrer">Baby Buddy for Android</a> (MIT) — scope baseline & inspiration. Not affiliated with either project.`,
      },
    }),
};

module.exports = config;
