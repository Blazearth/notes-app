/**
 * Colour system.
 *
 * Two independent axes, the way PennyWise splits `ThemeStyle` from
 * `AccentColor`:
 *
 * - **Surface family** — the neutral chrome. `weavr` is the warm-neutral set
 *   taken straight from the Claude Design mockups (its oklch values converted
 *   to sRGB); `rosepine` is the Rosé Pine Dawn/Main set ported from PennyWise.
 * - **Accent** — one hue applied on top, with a light and a dark variant.
 *   Rosé Pine Dawn supplies the light variants and Rosé Pine Main the dark
 *   ones, so an accent stays recognisable across a theme switch instead of
 *   glowing in dark mode.
 *
 * Every `on*` colour is *computed* against its background (see `contrast.ts`)
 * rather than tabulated, which is why 13 accents cost no extra colour data.
 */

import { bestOnColor, ensureContrast, mix, withAlpha } from './contrast';

export const ACCENT_IDS = [
  'weavr',
  'iris',
  'rose',
  'love',
  'gold',
  'pine',
  'foam',
  'highlight',
  'muted',
  'subtle',
  'text',
  'surface',
  'overlay',
] as const;

export type AccentId = (typeof ACCENT_IDS)[number];

type AccentTriad = { primary: string; secondary: string; tertiary: string };

export interface AccentSpec {
  id: AccentId;
  label: string;
  light: AccentTriad;
  dark: AccentTriad;
}

export const ACCENTS: Record<AccentId, AccentSpec> = {
  // The mockups' violet. Default, and the only accent not from Rosé Pine.
  weavr: {
    id: 'weavr',
    label: 'Weavr',
    light: { primary: '#5D69CB', secondary: '#3E8E8A', tertiary: '#C2705A' },
    dark: { primary: '#8D9DF5', secondary: '#6FC7C0', tertiary: '#E9A18B' },
  },
  iris: {
    id: 'iris',
    label: 'Iris',
    light: { primary: '#907AA9', secondary: '#7E9CB9', tertiary: '#B4637A' },
    dark: { primary: '#C4A7E7', secondary: '#9CCFD8', tertiary: '#EB6F92' },
  },
  rose: {
    id: 'rose',
    label: 'Rose',
    light: { primary: '#D7827E', secondary: '#D4A373', tertiary: '#C49B9B' },
    dark: { primary: '#EBBCBA', secondary: '#F6C177', tertiary: '#E0AFA0' },
  },
  love: {
    id: 'love',
    label: 'Love',
    light: { primary: '#B4637A', secondary: '#D7827E', tertiary: '#907AA9' },
    dark: { primary: '#EB6F92', secondary: '#EBBCBA', tertiary: '#C4A7E7' },
  },
  gold: {
    id: 'gold',
    label: 'Gold',
    light: { primary: '#EA9D34', secondary: '#D7827E', tertiary: '#C4956A' },
    dark: { primary: '#F6C177', secondary: '#EBBCBA', tertiary: '#E0B08A' },
  },
  pine: {
    id: 'pine',
    label: 'Pine',
    light: { primary: '#286983', secondary: '#56949F', tertiary: '#3B8686' },
    dark: { primary: '#31748F', secondary: '#9CCFD8', tertiary: '#569F9F' },
  },
  foam: {
    id: 'foam',
    label: 'Foam',
    light: { primary: '#56949F', secondary: '#286983', tertiary: '#6E9A6E' },
    dark: { primary: '#9CCFD8', secondary: '#31748F', tertiary: '#8EC9A0' },
  },
  highlight: {
    id: 'highlight',
    label: 'Highlight',
    light: { primary: '#C4A7E7', secondary: '#907AA9', tertiary: '#B4A0D1' },
    dark: { primary: '#C4A7E7', secondary: '#EB6F92', tertiary: '#B4A0D1' },
  },
  muted: {
    id: 'muted',
    label: 'Muted',
    light: { primary: '#797593', secondary: '#9893A5', tertiary: '#6E6A86' },
    dark: { primary: '#6E6A86', secondary: '#908CAA', tertiary: '#56526E' },
  },
  subtle: {
    id: 'subtle',
    label: 'Subtle',
    light: { primary: '#908CAA', secondary: '#797593', tertiary: '#A39FBF' },
    dark: { primary: '#908CAA', secondary: '#6E6A86', tertiary: '#ABA6C4' },
  },
  text: {
    id: 'text',
    label: 'Ink',
    light: { primary: '#575279', secondary: '#6E6A86', tertiary: '#4A4568' },
    dark: { primary: '#E0DEF4', secondary: '#908CAA', tertiary: '#C8C6DD' },
  },
  surface: {
    id: 'surface',
    label: 'Slate',
    light: { primary: '#6E6A86', secondary: '#797593', tertiary: '#575279' },
    dark: { primary: '#403D52', secondary: '#524F67', tertiary: '#2A2837' },
  },
  overlay: {
    id: 'overlay',
    label: 'Overlay',
    light: { primary: '#9893A5', secondary: '#908CAA', tertiary: '#7E7A96' },
    dark: { primary: '#524F67', secondary: '#6E6A86', tertiary: '#403D52' },
  },
};

export const SURFACE_FAMILY_IDS = ['weavr', 'rosepine'] as const;
export type SurfaceFamilyId = (typeof SURFACE_FAMILY_IDS)[number];

/** The neutral half of a palette — everything that is not the accent. */
interface SurfaceSet {
  background: string;
  surface: string;
  surfaceVariant: string;
  surfaceMuted: string;
  border: string;
  text: string;
  textMuted: string;
  textFaint: string;
  hatchA: string;
  hatchB: string;
  navBg: string;
  navActiveBg: string;
  navActiveText: string;
  navInactiveText: string;
  /** Candidate dark colour when picking readable text on an accent. */
  darkOn: string;
}

interface SurfaceFamily {
  id: SurfaceFamilyId;
  label: string;
  description: string;
  light: SurfaceSet;
  dark: SurfaceSet;
}

const WEAVR_FAMILY: SurfaceFamily = {
  id: 'weavr',
  label: 'Weavr',
  description: 'Warm paper neutrals — the designed default.',
  light: {
    background: '#F7F5F1',
    surface: '#FDFBFA',
    surfaceVariant: '#EDEBE7',
    surfaceMuted: '#EBE7E2',
    border: '#DEDAD5',
    text: '#14110D',
    textMuted: '#59544E',
    textFaint: '#B2ADA7',
    hatchA: '#E9E4DD',
    hatchB: '#DFDAD4',
    navBg: '#191511',
    navActiveBg: '#FDFBFA',
    navActiveText: '#14110D',
    navInactiveText: '#B2ADA7',
    darkOn: '#14110D',
  },
  dark: {
    background: '#0F0D09',
    surface: '#1A1814',
    surfaceVariant: '#262420',
    surfaceMuted: '#221F1B',
    border: '#35322E',
    text: '#EDEBE7',
    textMuted: '#A8A49F',
    textFaint: '#6C6864',
    hatchA: '#221F1B',
    hatchB: '#2B2825',
    navBg: '#302D29',
    navActiveBg: '#D9D7D4',
    navActiveText: '#14110D',
    navInactiveText: '#928F8A',
    darkOn: '#0F0D09',
  },
};

const ROSE_PINE_FAMILY: SurfaceFamily = {
  id: 'rosepine',
  label: 'Rosé Pine',
  description: 'Dawn in light, Rosé Pine in dark — muted and low-glare.',
  light: {
    background: '#FAF4ED',
    surface: '#FFFAF3',
    surfaceVariant: '#F2E9E1',
    surfaceMuted: '#F2E9E1',
    border: '#E6DDD5',
    text: '#575279',
    textMuted: '#6E6A86',
    textFaint: '#9893A5',
    hatchA: '#F2E9E1',
    hatchB: '#E6DDD5',
    navBg: '#26233A',
    navActiveBg: '#FFFAF3',
    navActiveText: '#575279',
    navInactiveText: '#908CAA',
    darkOn: '#575279',
  },
  dark: {
    background: '#191724',
    surface: '#1F1D2E',
    surfaceVariant: '#26233A',
    surfaceMuted: '#26233A',
    border: '#403D52',
    text: '#E0DEF4',
    textMuted: '#908CAA',
    textFaint: '#6E6A86',
    hatchA: '#26233A',
    hatchB: '#2A2837',
    navBg: '#403D52',
    navActiveBg: '#E0DEF4',
    navActiveText: '#191724',
    navInactiveText: '#908CAA',
    darkOn: '#191724',
  },
};

export const SURFACE_FAMILIES: Record<SurfaceFamilyId, SurfaceFamily> = {
  weavr: WEAVR_FAMILY,
  rosepine: ROSE_PINE_FAMILY,
};

/**
 * AMOLED override, applied on top of a dark surface set. True black background
 * with near-black elevated surfaces — PennyWise's `amoled_*` values, mapped
 * onto our slot names. Text colours stay with the family.
 */
const AMOLED_SURFACES = {
  background: '#000000',
  surface: '#121212',
  surfaceVariant: '#1E1E1E',
  surfaceMuted: '#1A1A1A',
  border: '#2A2A2A',
  hatchA: '#1A1A1A',
  hatchB: '#232323',
  navBg: '#1F1F1F',
} as const;

/** Semantic colours that do not follow the accent. Ported from PennyWise. */
const SEMANTIC = {
  light: { success: '#2E7D32', warning: '#E65100', danger: '#C62828' },
  dark: { success: '#81C784', warning: '#FFB74D', danger: '#E57373' },
} as const;

/**
 * Per-knowledge-type hues for Library chips and thumbnails. `structured_data`
 * is untyped JSONB on the server, so the type key is the only stable handle the
 * UI has — colour it consistently and a mixed grid stays scannable.
 */
export const TYPE_COLORS: Record<string, string> = {
  recipe: '#FF7043',
  workout: '#26A69A',
  restaurant: '#EF6C00',
  place: '#42A5F5',
  travel: '#5C6BC0',
  movie: '#AB47BC',
  book: '#7E57C2',
  music: '#EC407A',
  product: '#8D6E63',
  article: '#546E7A',
  note: '#78909C',
  other: '#90A4AE',
};

export interface Palette extends SurfaceSet {
  isDark: boolean;
  /** Fills: the FAB, progress bars, checkboxes. Raw accent, unmodified. */
  accent: string;
  /**
   * The accent *as text on the page background*. Identical to `accent` for most
   * combinations, lightened or darkened only where the raw accent would fail AA
   * (the neutral accents — Slate, Overlay, Muted — in dark mode).
   */
  accentText: string;
  onAccent: string;
  accentContainer: string;
  onAccentContainer: string;
  accentSecondary: string;
  accentTertiary: string;
  scrim: string;
  success: string;
  warning: string;
  danger: string;
}

export interface BuildPaletteOptions {
  family: SurfaceFamilyId;
  accent: AccentId;
  isDark: boolean;
  amoled: boolean;
}

export function buildPalette({ family, accent, isDark, amoled }: BuildPaletteOptions): Palette {
  const fam = SURFACE_FAMILIES[family] ?? WEAVR_FAMILY;
  const base = isDark ? fam.dark : fam.light;
  const surfaces: SurfaceSet = isDark && amoled ? { ...base, ...AMOLED_SURFACES } : base;

  const triad = (ACCENTS[accent] ?? ACCENTS.weavr)[isDark ? 'dark' : 'light'];
  const accentColor = triad.primary;

  // Container: pull the accent most of the way toward the page background.
  // Toward white in light mode keeps it as clean as the mockup's #E2E7FD.
  const accentContainer = isDark
    ? mix(accentColor, surfaces.background, 0.8)
    : mix(accentColor, '#FFFFFF', 0.85);

  // Prefer a tinted label on the container — it looks better than plain
  // black/white — with the palette ink and then pure black/white as fallbacks
  // for accents where the tint would not clear AA.
  const tinted = isDark ? mix(accentColor, '#FFFFFF', 0.35) : mix(accentColor, surfaces.darkOn, 0.55);
  const onAccentContainer = bestOnColor(accentContainer, [tinted, surfaces.darkOn]);

  const semantic = isDark ? SEMANTIC.dark : SEMANTIC.light;

  return {
    ...surfaces,
    // The nav pill's inactive label sits on an elevated fill, not the page, and
    // the family's own muted tone does not always clear AA there.
    navInactiveText: ensureContrast(surfaces.navBg, surfaces.navInactiveText),
    isDark,
    accent: accentColor,
    accentText: ensureContrast(surfaces.background, accentColor),
    onAccent: bestOnColor(accentColor, [surfaces.darkOn, '#FFFFFF']),
    accentContainer,
    onAccentContainer,
    accentSecondary: triad.secondary,
    accentTertiary: triad.tertiary,
    scrim: withAlpha(isDark ? '#000000' : surfaces.text, 0.55),
    ...semantic,
  };
}
