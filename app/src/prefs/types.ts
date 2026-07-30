import type { CoverId } from '@/theme/covers';
import type { AccentId, SurfaceFamilyId } from '@/theme/palettes';
import type { FontId } from '@/theme/typography';

export const THEME_MODES = ['system', 'light', 'dark'] as const;
export type ThemeMode = (typeof THEME_MODES)[number];

export const NAV_BAR_STYLES = ['floating', 'normal'] as const;
export type NavBarStyle = (typeof NAV_BAR_STYLES)[number];

export interface Preferences {
  /** Light / dark / follow the OS. */
  themeMode: ThemeMode;
  /** Neutral chrome family. */
  surfaceFamily: SurfaceFamilyId;
  /** Accent hue applied over the family. */
  accent: AccentId;
  /** True black surfaces in dark mode. */
  amoled: boolean;
  /** Display typeface. */
  font: FontId;
  /** Gradient wash behind the Home header. */
  cover: CoverId;
  /** Floating pill (as designed) or a docked bar. */
  navBarStyle: NavBarStyle;
  /** Translucent blur on the nav and sheets. Off = flat opaque fills. */
  blurEffects: boolean;
  /**
   * Tactile feedback on presses and selections. No effect on web, where there
   * is no haptics API, so the toggle is hidden rather than shown as a no-op.
   */
  haptics: boolean;
  /**
   * Capture flow: bring the app to the foreground when saving from the share
   * sheet. Off by default — silent capture is the designed behaviour.
   */
  openAppWhenSaving: boolean;
  /** Shown in the Home greeting. */
  userName: string;
}

export const DEFAULT_PREFERENCES: Preferences = {
  themeMode: 'system',
  surfaceFamily: 'weavr',
  accent: 'weavr',
  amoled: false,
  font: 'sora',
  cover: 'none',
  navBarStyle: 'floating',
  blurEffects: true,
  haptics: true,
  openAppWhenSaving: false,
  userName: '',
};

export const PREFERENCES_STORAGE_KEY = 'weavr.preferences.v1';
