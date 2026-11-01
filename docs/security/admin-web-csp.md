# Admin web — Content-Security-Policy

Status: v1.0. Security default from `docs/product/admin-web-design.md`'s approved decisions: "strict
CSP, no third-party scripts or analytics." This doc is the one place that answers "what's the policy,
and who's responsible for setting it in production."

## 1. What's shipped today

`frontend/admin/vite.config.ts` builds one CSP string from `VITE_API_BASE_URL` (see
`.env.example`) and applies it two ways:

1. **Dev server header** (`server.headers`, also `preview.headers`): a real `Content-Security-Policy`
   HTTP response header, sent by `vite dev`/`vite preview`. Verified with `curl -I` against a locally
   started dev server.
2. **`<meta http-equiv="Content-Security-Policy">`** injected into `index.html` at build time
   (`cspMetaPlugin`), so the built `dist/` output carries the same policy even if whoever serves those
   static files forgets to configure a header. Confirmed present in `dist/index.html` after `npm run
   build`.

The policy itself (`buildCsp` in `vite.config.ts`):

```
default-src 'self';
script-src 'self';
style-src 'self';
img-src 'self' data: <API_BASE_URL> <MAP_TILE_ORIGIN>;
font-src 'self';
connect-src 'self' <API_BASE_URL>;
frame-src 'none';
object-src 'none';
base-uri 'self';
form-action 'self'
```

No `'unsafe-inline'`, no `'unsafe-eval'`, no external script/style/font origin, no analytics beacon
origin - the app loads no third-party script by design (checked by hand: `grep -rn 'src=\"https' src`
turns up nothing, and the only external-looking reference anywhere is the API origin itself).
`img-src`/`connect-src` include the API origin because the document-viewer image and every API call
target it; `data:` in `img-src` is for nothing today but is harmless (inline data-URI images only, no
script execution vector) and avoids a future small-icon addition silently violating the policy.

`<MAP_TILE_ORIGIN>` is the origin of the Live map tile server: `https://tile.openstreetmap.org` by default,
or whatever `VITE_MAP_TILE_URL` points at (build time). It is allowed in `img-src` only (a tile is an image).
The tile URL must name one concrete host, no `{s}` subdomain placeholder, so no wildcard is ever needed. When
switching to a paid provider for public launch, set `VITE_MAP_TILE_URL` (and `VITE_MAP_ATTRIBUTION`) and
update the production header above to the same origin. `scripts/check-production-csp.mjs` allows exactly that
origin in `img-src` and still fails on any other third-party source.

## 2. What's NOT covered, and why that's a separate, production-only step

`frame-ancestors` (the modern replacement for `X-Frame-Options`, preventing the admin console from
being framed by a malicious site for clickjacking) **cannot be expressed in a `<meta>` tag at all** -
the CSP spec explicitly ignores it there. It only works as a real HTTP response header. Since
`frontend/admin` builds to static files with no server component of its own
(`vite build` → `dist/`), there is nothing in this repo that can emit that header in production - it
has to be set by whatever actually serves `dist/` (nginx, a CDN, a static-hosting platform's
header-rules config).

**The production header to set, at the reverse proxy/CDN level, once a real API origin replaces the
dev default:**

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https://api.ladygo.example https://tile.openstreetmap.org; font-src 'self'; connect-src 'self' https://api.ladygo.example; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

Replace `https://api.ladygo.example` with the actual production API origin. If document images move
to a direct S3/CDN origin instead of proxying through the API (`FileStorage.presignedDownloadUrl`),
add that exact origin to `img-src` too - never a wildcard like `https:`, which would defeat the point
of an allow-list.

## 3. Launch gate

Tracked in `docs/development/roadmap.md`'s "Launch gates" table: the dev-server header and the
build-time meta tag ship automatically with the code, but the **production HTTP header above
(specifically `frame-ancestors`) is a deploy-time step owned by whoever configures the production
host, not something this repo can enforce by itself** - it must be confirmed set before the admin web
is exposed anywhere outside a developer's own machine, pilot or public launch alike.
