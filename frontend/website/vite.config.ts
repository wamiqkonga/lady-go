import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

/**
 * Strict CSP, no third-party scripts/analytics - same policy and same reasoning as
 * frontend/admin/vite.config.ts (see that file's comment and docs/security/admin-web-csp.md for the
 * full rationale, which applies unchanged here). script-src/style-src allow only 'self' - no inline,
 * no eval, no external origin - except in dev serve mode, where @vitejs/plugin-react's Fast Refresh
 * preamble needs 'unsafe-inline'; the production build has no such inline script.
 *
 * style-src needs the same dev-only relaxation: Vite's dev server serves CSS by injecting it via a
 * JS-created <style> tag (for HMR), not a <link>, so a strict style-src with no 'unsafe-inline'
 * silently blocks every style in dev - confirmed by actually loading the dev server in a real browser
 * (a `curl` check of the header text alone would never catch this, same lesson as the script-src
 * comment above). The production build emits a real external .css file via <link rel="stylesheet">,
 * so this relaxation never reaches anything actually shipped - verified by check-production-csp.mjs
 * running against a real `vite build` output, not dev mode.
 */
function buildCsp(apiBaseUrl: string, isDevServer: boolean): string {
  return [
    "default-src 'self'",
    `script-src 'self'${isDevServer ? " 'unsafe-inline'" : ''}`,
    `style-src 'self'${isDevServer ? " 'unsafe-inline'" : ''}`,
    `img-src 'self' data: ${apiBaseUrl}`,
    "font-src 'self'",
    `connect-src 'self' ${apiBaseUrl}`,
    // The driver-registration selfie step uses getUserMedia (camera), never a frame - frame-src stays 'none'.
    "frame-src 'none'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ].join('; ')
}

function cspMetaPlugin(apiBaseUrl: string, isDevServer: boolean): Plugin {
  return {
    name: 'ladygo-csp-meta',
    transformIndexHtml(html) {
      const tag = `<meta http-equiv="Content-Security-Policy" content="${buildCsp(apiBaseUrl, isDevServer)}">`
      return html.replace('</head>', `    ${tag}\n  </head>`)
    },
  }
}

export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiBaseUrl = env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  const isDevServer = command === 'serve'
  const csp = buildCsp(apiBaseUrl, isDevServer)

  return {
    plugins: [react(), cspMetaPlugin(apiBaseUrl, isDevServer)],
    server: {
      port: 5173,
      strictPort: true,
      headers: { 'Content-Security-Policy': csp },
    },
    preview: {
      headers: { 'Content-Security-Policy': buildCsp(apiBaseUrl, false) },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      exclude: ['node_modules/**', 'e2e/**'],
    },
  }
})
