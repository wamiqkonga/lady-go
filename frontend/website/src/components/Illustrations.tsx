/** Hand-drawn vector scenes used across the site. Inline SVG on purpose: the production CSP only allows
 *  'self' and data: images, and SVG keeps them crisp at every screen size. Colours are fixed (not theme
 *  tokens) so the scenes look the same in light and dark mode; the backgrounds are transparent. */

function Sparkle({ x, y, s = 1, fill = '#ffffff' }: { x: number; y: number; s?: number; fill?: string }) {
  return (
    <path
      transform={`translate(${x} ${y}) scale(${s})`}
      fill={fill}
      d="M0 -10 C1.5 -3 3 -1.5 10 0 C3 1.5 1.5 3 0 10 C-1.5 3 -3 1.5 -10 0 C-3 -1.5 -1.5 -3 0 -10Z"
    />
  )
}

/** A woman driving a rose car through a pastel city: the home page hero. */
export function RideScene({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 480 400"
      role="img"
      aria-label="Illustration of a woman driving a rose-coloured car through a city"
    >
      <circle cx="240" cy="200" r="185" fill="#f6e4f5" />
      <circle cx="372" cy="86" r="38" fill="#ffe3c4" />
      <rect x="62" y="176" width="64" height="130" rx="10" fill="#e6d7f8" />
      <rect x="132" y="146" width="56" height="160" rx="10" fill="#ead0ee" />
      <rect x="296" y="160" width="70" height="146" rx="10" fill="#ffe3c4" />
      <rect x="372" y="190" width="54" height="116" rx="10" fill="#e6d7f8" />
      <g fill="#ffffff" opacity="0.85">
        <rect x="76" y="194" width="12" height="12" rx="3" />
        <rect x="98" y="194" width="12" height="12" rx="3" />
        <rect x="76" y="220" width="12" height="12" rx="3" />
        <rect x="98" y="220" width="12" height="12" rx="3" />
        <rect x="146" y="166" width="12" height="12" rx="3" />
        <rect x="164" y="166" width="12" height="12" rx="3" />
        <rect x="146" y="192" width="12" height="12" rx="3" />
        <rect x="164" y="192" width="12" height="12" rx="3" />
        <rect x="312" y="180" width="12" height="12" rx="3" />
        <rect x="336" y="180" width="12" height="12" rx="3" />
        <rect x="312" y="206" width="12" height="12" rx="3" />
        <rect x="336" y="206" width="12" height="12" rx="3" />
      </g>
      <rect x="30" y="306" width="420" height="46" rx="23" fill="#4a1d5a" />
      <g fill="#ffffff" opacity="0.7">
        <rect x="70" y="326" width="38" height="5" rx="2.5" />
        <rect x="150" y="326" width="38" height="5" rx="2.5" />
        <rect x="330" y="326" width="38" height="5" rx="2.5" />
        <rect x="410" y="326" width="24" height="5" rx="2.5" />
      </g>
      {/* car */}
      <path d="M118 262c0-18 12-30 30-34h150c20 4 34 16 36 34v22H118v-22z" fill="#6a2c70" />
      <path d="M168 228l24-46c3-6 8-9 14-9h74c7 0 12 3 16 9l28 46H168z" fill="#8a4a90" />
      <path d="M190 226l18-35c2-4 5-6 9-6h60c5 0 8 2 11 6l20 35H190z" fill="#fbf3fb" />
      {/* captain */}
      <circle cx="262" cy="206" r="15" fill="#f4b9a0" />
      <path d="M246 204c-2-18 10-27 22-26 11 1 17 9 14 21-4-8-10-12-18-12-8 0-14 5-18 17z" fill="#2b2030" />
      <circle cx="279" cy="184" r="8" fill="#2b2030" />
      <path d="M240 226c2-10 10-14 22-14s20 4 22 14H240z" fill="#4a1d5a" />
      <path d="M258 209c2 3 6 3 8 0" fill="none" stroke="#6a2c70" strokeWidth="2" strokeLinecap="round" />
      <path d="M118 270h262" stroke="#4a1d5a" strokeWidth="3" opacity="0.5" />
      <circle cx="372" cy="262" r="7" fill="#ffd27a" />
      <circle cx="132" cy="262" r="6" fill="#ff8a80" />
      {[168, 336].map((cx) => (
        <g key={cx}>
          <circle cx={cx} cy="290" r="27" fill="#2b2030" />
          <circle cx={cx} cy="290" r="13" fill="#f6e4f5" />
          <circle cx={cx} cy="290" r="4" fill="#6a2c70" />
        </g>
      ))}
      {/* floating pin, heart and sparkles */}
      <g>
        <path d="M410 120c0-18-11-30-26-30s-26 12-26 30c0 22 26 44 26 44s26-22 26-44z" fill="#c74b6e" />
        <circle cx="384" cy="120" r="10" fill="#ffffff" />
      </g>
      <path
        transform="translate(64 96) scale(2.1)"
        fill="#ff8fab"
        d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
      />
      <Sparkle x={150} y={80} s={1.4} fill="#ffffff" />
      <Sparkle x={300} y={60} s={1} fill="#ffffff" />
      <Sparkle x={436} y={236} s={1.1} fill="#ffffff" />
    </svg>
  )
}

/** A woman captain with a phone and a verified-documents card: the captain pages. */
export function DriverScene({ className }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 420 360"
      role="img"
      aria-label="Illustration of a woman captain registering from her phone"
    >
      <circle cx="210" cy="184" r="160" fill="#ffffff" opacity="0.22" />
      {/* woman */}
      <path d="M62 330c2-52 34-84 78-84s76 32 78 84H62z" fill="#4a1d5a" />
      <path d="M118 246h44v26c0 8-10 14-22 14s-22-6-22-14v-26z" fill="#e9a58c" />
      <ellipse cx="140" cy="206" rx="40" ry="46" fill="#f4b9a0" />
      <path d="M98 204c-6-44 22-64 48-62 28 2 44 26 34 62-6-18-16-28-30-30-18 6-38 8-52 30z" fill="#2b2030" />
      <circle cx="176" cy="150" r="17" fill="#2b2030" />
      <path d="M124 222c6 8 22 8 28 0" fill="none" stroke="#6a2c70" strokeWidth="3" strokeLinecap="round" />
      <circle cx="124" cy="206" r="3" fill="#2b2030" />
      <circle cx="156" cy="206" r="3" fill="#2b2030" />
      <path d="M112 262l28 22 28-22" fill="none" stroke="#f6e4f5" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      {/* phone */}
      <rect x="232" y="64" width="128" height="236" rx="22" fill="#ffffff" />
      <rect x="244" y="84" width="104" height="196" rx="12" fill="#fbf3fb" />
      <rect x="276" y="72" width="40" height="5" rx="2.5" fill="#d9c9d3" />
      <circle cx="296" cy="128" r="26" fill="#6a2c70" />
      <path d="M284 128l9 9 18-19" fill="none" stroke="#ffffff" strokeWidth="6" strokeLinecap="round" strokeLinejoin="round" />
      <rect x="256" y="172" width="80" height="12" rx="6" fill="#f2c4d4" />
      <rect x="256" y="194" width="62" height="10" rx="5" fill="#f9dce6" />
      <rect x="256" y="224" width="80" height="34" rx="17" fill="#8a4a90" />
      <rect x="276" y="236" width="40" height="10" rx="5" fill="#ffffff" />
      {/* floating document cards */}
      <g transform="rotate(-8 372 120)">
        <rect x="350" y="92" width="58" height="42" rx="8" fill="#ffe3c4" />
        <circle cx="366" cy="108" r="6" fill="#8a4a90" />
        <rect x="378" y="104" width="22" height="5" rx="2.5" fill="#d9a77a" />
        <rect x="360" y="120" width="38" height="5" rx="2.5" fill="#d9a77a" />
      </g>
      <g transform="rotate(7 200 90)">
        <rect x="170" y="62" width="58" height="42" rx="8" fill="#e6d7f8" />
        <rect x="180" y="74" width="38" height="5" rx="2.5" fill="#b69be0" />
        <rect x="180" y="86" width="26" height="5" rx="2.5" fill="#b69be0" />
      </g>
      <Sparkle x={52} y={110} s={1.3} />
      <Sparkle x={386} y={252} s={1.1} />
      <Sparkle x={214} y={40} s={0.9} />
    </svg>
  )
}

/** Soft blob with sparkles behind a big icon: the header art for the text pages. */
export function PageArt({ children }: { children: React.ReactNode }) {
  return (
    <div className="page-art" aria-hidden="true">
      <svg viewBox="0 0 240 240" focusable="false">
        <path
          d="M120 14c44 0 90 22 100 70s-14 100-62 128-110 16-136-30S4 80 40 44 84 14 120 14z"
          fill="#f6e4f5"
        />
        <circle cx="206" cy="46" r="14" fill="#ffe3c4" />
        <circle cx="34" cy="196" r="10" fill="#e6d7f8" />
        <Sparkle x={196} y={176} s={1.2} fill="#ffffff" />
        <Sparkle x={52} y={56} s={0.9} fill="#ffffff" />
      </svg>
      <span className="page-art-icon">{children}</span>
    </div>
  )
}
