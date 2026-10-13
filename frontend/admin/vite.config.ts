import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

/**
 * Strict CSP, no third-party scripts/analytics (docs/product/admin-web-design.md security defaults;
 * docs/security/admin-web-csp.md has the full rationale and the production header to set at
 * whichever reverse proxy/CDN actually serves the built `dist/` output - a static build has no
 * server of its own to attach a response header to, which is why `frame-ancestors` (meta tags can't
 * carry it at all, per the CSP spec) is called out separately there).
 *
 * script-src/style-src allow only 'self' - no inline, no eval, no external origin - EXCEPT in dev
 * serve mode, where @vitejs/plugin-react injects an inline <script> (the React Fast Refresh
 * "preamble") into index.html; without 'unsafe-inline' on script-src there, the browser silently
 * blocks that inline script and the app never mounts at all ("can't detect preamble", caught by
 * actually loading the dev server in a real browser via the Playwright E2E suite - a plain `curl`
 * check of the response headers, which is all that was verified before, can't catch this). The
 * production build has no such inline script (confirmed: `grep '<script' dist/index.html` after
 * `vite build` shows only external `src=` references), so `vite build`/`vite preview` keep the
 * strict policy - only `vite dev`'s own tooling gets the relaxation, never anything served to a
 * real admin. connect-src/img-src include the configured API origin, since fetches and the
 * document-viewer image both target it.
 */
function buildCsp(apiBaseUrl: string, isDevServer: boolean): string {
  return [
    "default-src 'self'",
    `script-src 'self'${isDevServer ? " 'unsafe-inline'" : ''}`,
    "style-src 'self'",
    `img-src 'self' data: ${apiBaseUrl}`,
    "font-src 'self'",
    `connect-src 'self' ${apiBaseUrl}`,
    "frame-src 'none'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
}

/** Injects the CSP as a <meta http-equiv> tag so it's enforced even when the static host serving
 *  `dist/` isn't configured to add the header itself - belt, not suspenders: the HTTP header
 *  (documented for production in docs/security/admin-web-csp.md) is still what should actually be
 *  set, since a meta tag can't carry frame-ancestors at all. */
function cspMetaPlugin(apiBaseUrl: string, isDevServer: boolean): Plugin {
  return {
    name: 'ladygo-csp-meta',
    transformIndexHtml(html) {
      const tag = `<meta http-equiv="Content-Security-Policy" content="${buildCsp(apiBaseUrl, isDevServer)}">`
      return html.replace('</head>', `    ${tag}\n  </head>`)
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiBaseUrl = env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  const isDevServer = command === 'serve'
  const csp = buildCsp(apiBaseUrl, isDevServer)

  return {
    plugins: [react(), cspMetaPlugin(apiBaseUrl, isDevServer)],
    server: {
      port: 5174,
      strictPort: true,
      headers: { 'Content-Security-Policy': csp },
    },
    preview: {
      // vite preview serves the real production build - never the relaxed dev-only policy.
      headers: { 'Content-Security-Policy': buildCsp(apiBaseUrl, false) },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      // e2e/ holds Playwright specs (run via `npm run e2e`, a separate process/runner entirely) -
      // vitest's default include pattern also matches *.spec.ts, so without this it tries to run
      // them too and fails importing '@playwright/test' as if it were a vitest test.
      exclude: ['node_modules/**', 'e2e/**'],
    },
  }
})
