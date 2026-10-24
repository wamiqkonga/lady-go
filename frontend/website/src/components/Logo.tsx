/** The Lady Go mark: a shield (safety), a heart (care) and a rose spark (the ride in motion). */
export default function Logo({ size = 32 }: { size?: number }) {
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 96 96" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="lg-shield" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#8b3f92" />
          <stop offset="1" stopColor="#4a1d5a" />
        </linearGradient>
      </defs>
      <path d="M48 6c14 0 28 5 34 8v30c0 20-16 36-34 46C30 80 14 64 14 44V14c6-3 20-8 34-8z" fill="url(#lg-shield)" />
      <path
        className="logo-heart"
        transform="translate(30 25) scale(1.5)"
        fill="#fff"
        d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
      />
      <circle className="logo-spark" cx="72" cy="22" r="4" fill="#ff8fab" />
    </svg>
  )
}
