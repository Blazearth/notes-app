/**
 * Groups itinerary places by day, ordered chronologically — replacing a flat
 * list of every place with the structure a person actually reads a trip by.
 *
 * Grouping by `area` was tried first and rejected after checking it against
 * real data: the registry defines a place's `area` as "neighbourhood or
 * district" *within* that place, not the region it belongs to — so a place
 * named "Tokyo" with `area: "Yanaka"` grouped as a lone entry under a
 * "YANAKA" heading, which reads backwards (Tokyo filed under its own
 * neighbourhood). `day`, by contrast, is unambiguous and is the axis an
 * itinerary is actually organised around — matching what "45 identical
 * cards" really needs fixing: when you're going, not an invented region.
 *
 * Operates on indices into the caller's own array rather than owning a
 * shape, so a merged collection's entities and one source save's raw
 * `places` array can both use it without a shared item type.
 *
 * Pure: no store, no theme, no React.
 */

export interface DayGroupFacts {
  /** Already cleaned — null when the place states no day. */
  day: string | null;
}

export interface DayGroup {
  /** "Day 1–4", "Day 5", or "Unscheduled" when nothing in the group states a day. */
  label: string;
  /** Positions into the caller's own array, in the order they should render. */
  indices: number[];
}

const UNSCHEDULED = 'Unscheduled';

/** "1-4" / "1–4" / "1 to 4" -> "Day 1–4"; a bare "5" -> "Day 5". Never invents a range from one number. */
function dayLabel(day: string): string {
  const nums = day.match(/\d+/g);
  if (!nums || nums.length === 0) return `Day ${day}`;
  if (nums.length === 1) return `Day ${nums[0]}`;
  const min = Math.min(...nums.map(Number));
  const max = Math.max(...nums.map(Number));
  return min === max ? `Day ${min}` : `Day ${min}–${max}`;
}

export function groupByDay(items: DayGroupFacts[]): DayGroup[] {
  const order: string[] = [];
  const byKey = new Map<string, number[]>();
  items.forEach((item, index) => {
    const key = item.day?.trim() || UNSCHEDULED;
    const existing = byKey.get(key);
    if (existing) {
      existing.push(index);
    } else {
      byKey.set(key, [index]);
      order.push(key);
    }
  });

  const built = order.map((key) => {
    const indices = byKey.get(key) as number[];
    const firstNum = key === UNSCHEDULED ? null : key.match(/\d+/)?.[0];
    return {
      label: key === UNSCHEDULED ? UNSCHEDULED : dayLabel(key),
      indices,
      sortKey: firstNum ? Number(firstNum) : Number.MAX_SAFE_INTEGER,
      isUnscheduled: key === UNSCHEDULED,
    };
  });

  // "Unscheduled" (no stated day) always sorts last — it has no day signal
  // to place it among the groups that do.
  built.sort((a, b) => {
    if (a.isUnscheduled) return b.isUnscheduled ? 0 : 1;
    if (b.isUnscheduled) return -1;
    return a.sortKey - b.sortKey || a.label.localeCompare(b.label);
  });

  return built.map(({ label, indices }) => ({ label, indices }));
}
