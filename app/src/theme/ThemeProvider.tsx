import * as SystemUI from 'expo-system-ui';
import React, { createContext, useContext, useEffect, useMemo } from 'react';
import { useColorScheme } from 'react-native';

import { usePreferences } from '@/prefs/PreferencesProvider';
import type { NavBarStyle } from '@/prefs/types';
import { COVERS, type CoverSpec } from './covers';
import { buildPalette, type Palette } from './palettes';
import { Alpha, Duration, Elevation, IconSize, Layout, Radius, Spacing } from './tokens';
import { buildTypography, type Typography } from './typography';

export interface Theme {
  palette: Palette;
  type: Typography;
  spacing: typeof Spacing;
  radius: typeof Radius;
  layout: typeof Layout;
  icon: typeof IconSize;
  alpha: typeof Alpha;
  elevation: typeof Elevation;
  duration: typeof Duration;
  /** Personalisation that components read directly. */
  cover: CoverSpec;
  navBarStyle: NavBarStyle;
  blurEffects: boolean;
}

const ThemeContext = createContext<Theme | null>(null);

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const { prefs } = usePreferences();
  const systemScheme = useColorScheme();

  const isDark = prefs.themeMode === 'system' ? systemScheme === 'dark' : prefs.themeMode === 'dark';

  const theme = useMemo<Theme>(() => {
    const palette = buildPalette({
      family: prefs.surfaceFamily,
      accent: prefs.accent,
      isDark,
      amoled: prefs.amoled,
    });
    return {
      palette,
      type: buildTypography(prefs.font),
      spacing: Spacing,
      radius: Radius,
      layout: Layout,
      icon: IconSize,
      alpha: Alpha,
      elevation: Elevation,
      duration: Duration,
      cover: COVERS[prefs.cover] ?? COVERS.none,
      navBarStyle: prefs.navBarStyle,
      blurEffects: prefs.blurEffects,
    };
  }, [prefs.surfaceFamily, prefs.accent, prefs.amoled, prefs.font, prefs.cover, prefs.navBarStyle, prefs.blurEffects, isDark]);

  // Paint the window behind the React root too, otherwise a theme switch
  // flashes the platform default during navigation transitions.
  useEffect(() => {
    SystemUI.setBackgroundColorAsync(theme.palette.background).catch(() => {});
  }, [theme.palette.background]);

  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

export function useTheme(): Theme {
  const theme = useContext(ThemeContext);
  if (!theme) throw new Error('useTheme must be used inside <ThemeProvider>');
  return theme;
}
