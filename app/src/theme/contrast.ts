/**
 * WCAG relative-luminance contrast picking, ported from PennyWise's
 * `Theme.kt` (`relativeLuminance` / `contrastRatio` / `onColorFor`).
 *
 * This is what makes a free-choice accent palette safe: every accent gets an
 * `on*` colour computed against it rather than hardcoded, so a pale Gold and a
 * deep Pine both stay readable without per-accent tables.
 */

function parseHex(hex: string): [number, number, number] {
  const h = hex.replace('#', '');
  const full =
    h.length === 3
      ? h
          .split('')
          .map((c) => c + c)
          .join('')
      : h;
  return [
    parseInt(full.slice(0, 2), 16) / 255,
    parseInt(full.slice(2, 4), 16) / 255,
    parseInt(full.slice(4, 6), 16) / 255,
  ];
}

function linearize(c: number): number {
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

export function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHex(hex);
  return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
}

export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/**
 * Pick whichever of `darkOn` / `lightOn` reads better on `background`.
 */
export function onColorFor(background: string, darkOn: string, lightOn = '#FFFFFF'): string {
  return contrastRatio(background, lightOn) >= contrastRatio(background, darkOn) ? lightOn : darkOn;
}

/** WCAG AA for normal-size text. */
export const AA_CONTRAST = 4.5;

/**
 * Like `onColorFor`, but guaranteed to clear `min` where that is possible at all.
 *
 * Picking the *better* of two candidates is not the same as picking a readable
 * one: on a pale accent such as Rosé Pine Dawn's Rose (#D7827E), white scores
 * 2.1:1 and the palette's own ink 2.8:1 — the better of the two still fails.
 * So the tinted candidates are tried in order, and if none qualifies this falls
 * through to pure black or white, which always clears AA on a mid-tone.
 */
export function bestOnColor(background: string, candidates: string[], min = AA_CONTRAST): string {
  for (const candidate of candidates) {
    if (contrastRatio(background, candidate) >= min) return candidate;
  }
  return contrastRatio(background, '#000000') >= contrastRatio(background, '#FFFFFF')
    ? '#000000'
    : '#FFFFFF';
}

/**
 * Keep `color` but lighten or darken it just enough to clear `min` on
 * `background`.
 *
 * `bestOnColor` swaps in a different colour; this one preserves the hue, which
 * is what an accent used *as text* needs — Rosé Pine's Slate accent on its own
 * dark background is 1.7:1, and replacing it with white would stop reading as
 * an accent at all.
 */
export function ensureContrast(background: string, color: string, min = AA_CONTRAST): string {
  if (contrastRatio(background, color) >= min) return color;
  const target = relativeLuminance(background) > 0.5 ? '#000000' : '#FFFFFF';
  for (let step = 1; step <= 10; step += 1) {
    const candidate = mix(color, target, step / 10);
    if (contrastRatio(background, candidate) >= min) return candidate;
  }
  return target;
}

/** `#RRGGBB` + opacity → `rgba(...)`, since RN has no colour-mix function. */
export function withAlpha(hex: string, alpha: number): string {
  const [r, g, b] = parseHex(hex);
  return `rgba(${Math.round(r * 255)}, ${Math.round(g * 255)}, ${Math.round(b * 255)}, ${alpha})`;
}

/** Blend `hex` toward `toward` by `amount` (0–1) in sRGB. Used for containers. */
export function mix(hex: string, toward: string, amount: number): string {
  const [r1, g1, b1] = parseHex(hex);
  const [r2, g2, b2] = parseHex(toward);
  const ch = (a: number, b: number) =>
    Math.round((a + (b - a) * amount) * 255)
      .toString(16)
      .padStart(2, '0');
  return `#${ch(r1, r2)}${ch(g1, g2)}${ch(b1, b2)}`.toUpperCase();
}
