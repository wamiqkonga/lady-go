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
 * script-src/style-src allow only 'self' - no inline, no eval, no external origin. connect-src/img-src
 * include the configured API origin, since fetches and document-viewer images both target it.
 */
function buildCsp(apiBaseUrl: string): string {
  return [
    "default-src 'self'",
    "script-src 'self'",
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
function cspMetaPlugin(apiBaseUrl: string): Plugin {
  return {
    name: 'ladygo-csp-meta',
    transformIndexHtml(html) {
      const tag = `<meta http-equiv="Content-Security-Policy" content="${buildCsp(apiBaseUrl)}">`
      return html.replace('</head>', `    ${tag}\n  </head>`)
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiBaseUrl = env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  const csp = buildCsp(apiBaseUrl)

  return {
    plugins: [react(), cspMetaPlugin(apiBaseUrl)],
    server: {
      port: 5174,
      strictPort: true,
      headers: { 'Content-Security-Policy': csp },
    },
    preview: {
      headers: { 'Content-Security-Policy': csp },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  }
})
