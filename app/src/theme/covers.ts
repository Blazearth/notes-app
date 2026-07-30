/**
 * Cover gradients, ported from PennyWise's `getCoverGradientColors`.
 *
 * A cover is a wide gradient washed behind the top of Home at low opacity. It
 * is the cheapest personalisation in the app — no layout change, no extra
 * component — and the one users notice first.
 */

export const COVER_IDS = [
  'none',
  'aurora',
  'sunset',
  'ocean',
  'forest',
  'lavender',
  'midnight',
  'roseGold',
  'northernLights',
] as const;

export type CoverId = (typeof COVER_IDS)[number];

export interface CoverSpec {
  id: CoverId;
  label: string;
  colors: string[];
}

export const COVERS: Record<CoverId, CoverSpec> = {
  none: { id: 'none', label: 'None', colors: [] },
  aurora: { id: 'aurora', label: 'Aurora', colors: ['#7B2FBE', '#2DD4BF', '#EC4899'] },
  sunset: { id: 'sunset', label: 'Sunset', colors: ['#F97316', '#EC4899', '#8B5CF6'] },
  ocean: { id: 'ocean', label: 'Ocean', colors: ['#1E3A5F', '#06B6D4', '#14B8A6'] },
  forest: { id: 'forest', label: 'Forest', colors: ['#064E3B', '#059669', '#84CC16'] },
  lavender: { id: 'lavender', label: 'Lavender mist', colors: ['#A78BFA', '#F9A8D4', '#F3E8FF'] },
  midnight: { id: 'midnight', label: 'Midnight', colors: ['#1E1B4B', '#3730A3', '#7C3AED'] },
  roseGold: { id: 'roseGold', label: 'Rose gold', colors: ['#FDA4AF', '#FBBF24', '#FED7AA'] },
  northernLights: {
    id: 'northernLights',
    label: 'Northern lights',
    colors: ['#10B981', '#06B6D4', '#3B82F6', '#8B5CF6'],
  },
};

/**
 * A gradient needs two stops minimum for `expo-linear-gradient`; `none`
 * returns an empty array and callers skip rendering entirely.
 */
export function coverColors(id: CoverId): string[] {
  return COVERS[id]?.colors ?? [];
}
