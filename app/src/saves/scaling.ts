/**
 * Recipe serving scaling — deterministic local compute, never a model call
 * (`docs/next-phases.md` §4.2). Scales only what parses cleanly ("250g" →
 * "375g" at 1.5×); anything else ("a pinch", "to taste") passes through
 * unscaled rather than being guessed at.
 */

/** Leading number (plain or a simple fraction like "1/2"), then whatever text follows. */
const LEADING_QUANTITY = /^(\d+(?:\.\d+)?)(?:\s*\/\s*(\d+(?:\.\d+)?))?/;

export function scaleQuantity(quantity: string, factor: number): string {
  const match = quantity.match(LEADING_QUANTITY);
  if (!match) return quantity;

  const whole = parseFloat(match[1]);
  const denominator = match[2] ? parseFloat(match[2]) : null;
  const value = denominator ? whole / denominator : whole;
  const scaled = value * factor;
  const rest = quantity.slice(match[0].length);

  // Up to two decimals, trailing zeros trimmed — "1.50" reads worse than "1.5".
  const formatted = Number.isInteger(scaled)
    ? String(scaled)
    : scaled.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');

  return `${formatted}${rest}`;
}

/** The recipe's stated serving count, or `null` when it isn't a plain number to scale from. */
export function baseServings(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const match = value.match(/^\d+/);
  if (!match) return null;
  const n = parseInt(match[0], 10);
  return Number.isFinite(n) && n > 0 ? n : null;
}
