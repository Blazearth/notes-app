/**
 * Type scale, transcribed from the mockups.
 *
 * The mockups pair **Sora** for display/label text with the platform sans for
 * body copy. React Native cannot synthesise weights from one family the way a
 * browser can, so each Sora weight is its own `fontFamily` — hence the `face()`
 * indirection instead of plain `fontWeight`.
 *
 * `letterSpacing` in the mockups is in `em`; RN wants points, so the
 * uppercase-label tracking is pre-multiplied (0.03em × 12px ≈ 0.36).
 */

import type { TextStyle } from 'react-native';

export const FONT_IDS = ['sora', 'system'] as const;
export type FontId = (typeof FONT_IDS)[number];

export const FONT_LABELS: Record<FontId, string> = {
  sora: 'Sora',
  system: 'System',
};

type Weight = '500' | '600' | '700';

const SORA_FACES: Record<Weight, string> = {
  '500': 'Sora_500Medium',
  '600': 'Sora_600SemiBold',
  '700': 'Sora_700Bold',
};

export interface Typography {
  /** Screen titles — "Library", the greeting name. */
  display: TextStyle;
  /** Section/space titles inside a screen. */
  title: TextStyle;
  /** Sheet and dialog headings. */
  heading: TextStyle;
  /** Card and list-row titles. */
  cardTitle: TextStyle;
  /** Uppercase section labels above a group. */
  sectionLabel: TextStyle;
  /** Default body copy. */
  body: TextStyle;
  /** Dense body copy inside cards and rows. */
  bodySmall: TextStyle;
  /** Secondary metadata line. */
  caption: TextStyle;
  /** Chip and tab labels. */
  label: TextStyle;
  /** Bottom-nav labels. */
  navLabel: TextStyle;
  /** Monospace stand-in labels in placeholder thumbnails. */
  mono: TextStyle;
}

export function buildTypography(font: FontId): Typography {
  // For `system`, leaving fontFamily undefined lets RN use San Francisco /
  // Roboto and honour fontWeight directly.
  const face = (weight: Weight): TextStyle =>
    font === 'sora' ? { fontFamily: SORA_FACES[weight] } : { fontWeight: weight };

  return {
    display: { ...face('700'), fontSize: 24, lineHeight: 30 },
    title: { ...face('700'), fontSize: 22, lineHeight: 28 },
    heading: { ...face('700'), fontSize: 17, lineHeight: 22 },
    cardTitle: { ...face('600'), fontSize: 12.5, lineHeight: 17 },
    sectionLabel: {
      ...face('600'),
      fontSize: 12,
      lineHeight: 16,
      letterSpacing: 0.36,
      textTransform: 'uppercase',
    },
    body: { fontSize: 13.5, lineHeight: 20 },
    bodySmall: { fontSize: 12.5, lineHeight: 17, fontWeight: '500' },
    caption: { fontSize: 11, lineHeight: 15 },
    label: { ...face('600'), fontSize: 12.5, lineHeight: 16 },
    navLabel: { ...face('600'), fontSize: 11.5, lineHeight: 14 },
    mono: { fontSize: 9, lineHeight: 12, fontFamily: undefined },
  };
}
