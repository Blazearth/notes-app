/**
 * Layout tokens. Values are lifted from the Claude Design mockups
 * (`Weavr Mobile App.dc.html`) so the implementation matches pixel for pixel,
 * organised the way PennyWise organises its `Spacing`/`Dimensions` objects.
 */

export const Spacing = {
  none: 0,
  xxs: 2,
  xs: 4,
  sm: 8,
  smd: 10,
  md: 12,
  lg: 16,
  xl: 20,
  xxl: 24,
  xxxl: 32,
} as const;

export const Radius = {
  xs: 5,
  sm: 10,
  md: 14,
  lg: 16,
  xl: 24,
  pill: 100,
} as const;

export const Layout = {
  /** Screen gutter — the mockups use 20px. */
  screenGutter: 20,
  /** Room under the scroll content so the floating nav never covers a card. */
  navScrollInset: 140,
  /** Inset of the floating nav from the screen edges. */
  navInset: 16,
  navHeight: 52,
  navHeightNormal: 64,
  fabSize: 52,
  cardPadding: 14,
  rowPadding: 12,
  coverHeight: 300,
  minTouchTarget: 44,
} as const;

export const IconSize = {
  sm: 14,
  md: 18,
  lg: 20,
  row: 40,
  tile: 56,
  empty: 96,
} as const;

export const Alpha = {
  scrim: 0.55,
  navBar: 0.94,
  cover: 0.45,
  coverPreview: 0.75,
  disabled: 0.38,
  divider: 0.12,
  pressed: 0.6,
} as const;

export const Duration = {
  short: 120,
  medium: 240,
  long: 400,
} as const;

/**
 * Shadows are the one place the mockups and native diverge: CSS box-shadow has
 * no direct React Native equivalent on Android, where only `elevation` renders.
 * Each entry carries both so a single spread works on either platform.
 */
export const Elevation = {
  none: { elevation: 0, shadowOpacity: 0 },
  card: {
    elevation: 1,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 2,
    shadowOffset: { width: 0, height: 1 },
  },
  nav: {
    elevation: 12,
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 8 },
  },
  fab: {
    elevation: 10,
    shadowOpacity: 0.45,
    shadowRadius: 20,
    shadowOffset: { width: 0, height: 8 },
  },
  sheet: {
    elevation: 24,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 40,
    shadowOffset: { width: 0, height: -12 },
  },
} as const;
