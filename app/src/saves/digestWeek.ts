/**
 * Days remaining in the Weekly digest's own week — pure arithmetic over
 * `DigestResponse.weekStart`, the server's own Monday-UTC boundary
 * (`UsageService.weekStart()`, see CLAUDE.md's digest section) rather than a
 * second, client-computed one that could drift from it.
 *
 * Pure: no store, no theme, no React. Executed under node against fixtures.
 */

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/**
 * Days remaining in the digest's Mon–Sun week, today counted as one of them.
 * Clamped to [1, 7] so a stale or clock-skewed digest never reads as "0 days
 * left" or negative — a wrong-but-plausible number is a smaller UI wart than
 * an obviously broken one.
 */
export function daysLeftInWeek(weekStart: string, now: Date = new Date()): number {
  const start = new Date(weekStart).getTime();
  if (Number.isNaN(start)) return 7;
  const today = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const elapsedDays = Math.floor((today - start) / MS_PER_DAY);
  return Math.min(7, Math.max(1, 7 - elapsedDays));
}
