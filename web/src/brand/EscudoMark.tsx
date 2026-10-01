/**
 * The Escudo mark: a shield with a winding road — "we get you back on the road".
 * Vector redraw of public/brand/escudo-logo.jpg (the chosen "A · Sereno" logo).
 */
export function EscudoMark({ size = 32, title }: { size?: number; title?: string }) {
  return (
    <svg
      width={size}
      height={(size * 56) / 48}
      viewBox="0 0 48 56"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
    >
      <defs>
        <clipPath id="escudo-shield">
          <path d="M9 5.5C18 4 30 4 39 5.5C42 6 44 8 44 11V26C44 39.5 35.5 48.5 24 54C12.5 48.5 4 39.5 4 26V11C4 8 6 6 9 5.5Z" />
        </clipPath>
      </defs>
      <path d="M9 5.5C18 4 30 4 39 5.5C42 6 44 8 44 11V26C44 39.5 35.5 48.5 24 54C12.5 48.5 4 39.5 4 26V11C4 8 6 6 9 5.5Z" fill="var(--pine, #0F5C4D)" />
      <g clipPath="url(#escudo-shield)" fill="none" strokeLinecap="round">
        <path d="M12 49C21 45 33 39 29 31C26 25 17 25 19 18.5C21 13 31 11 45 11" stroke="var(--mist, #F3F6F4)" strokeWidth="6" />
        <path d="M12 49C21 45 33 39 29 31C26 25 17 25 19 18.5C21 13 31 11 45 11" stroke="var(--pine, #0F5C4D)" strokeWidth="1.1" />
      </g>
    </svg>
  );
}

export function EscudoLogo({ size = 28 }: { size?: number }) {
  return (
    <span className="brand" style={{ fontSize: size * 0.9 }}>
      <EscudoMark size={size} />
      <span className="brand-word">Escudo</span>
    </span>
  );
}
