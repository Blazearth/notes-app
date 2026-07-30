import { BlurView } from 'expo-blur';
import React from 'react';
import { Platform, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { withAlpha } from '@/theme/contrast';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Glyph } from './Glyph';

export interface NavItem {
  key: string;
  label: string;
}

export interface BottomNavProps {
  items: NavItem[];
  activeKey: string;
  onSelect: (key: string) => void;
  onCapture: () => void;
}

/**
 * Home / Library / Spaces plus the single Capture action.
 *
 * Two shapes, chosen by the `navBarStyle` preference (PennyWise's
 * `NavBarStyle`): `floating` is the designed inverse-ink pill inset from the
 * screen edges; `normal` is a docked bar for people who dislike content
 * scrolling under a floating element.
 */
export function BottomNav({ items, activeKey, onSelect, onCapture }: BottomNavProps) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { palette, radius, spacing, layout, elevation, navBarStyle, blurEffects } = theme;

  const floating = navBarStyle === 'floating';

  const fab = (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Capture"
      onPress={onCapture}
      style={({ pressed }) => [
        {
          width: layout.fabSize,
          height: layout.fabSize,
          borderRadius: layout.fabSize / 2,
          backgroundColor: palette.accent,
          alignItems: 'center',
          justifyContent: 'center',
          ...elevation.fab,
          shadowColor: palette.accent,
        },
        pressed && { transform: [{ scale: 0.94 }] },
      ]}
    >
      <Glyph name="plus" size={16} weight={2} color={palette.onAccent} />
    </Pressable>
  );

  const tabs = items.map((item) => {
    const active = item.key === activeKey;
    return (
      <Pressable
        key={item.key}
        accessibilityRole="tab"
        accessibilityState={{ selected: active }}
        accessibilityLabel={item.label}
        onPress={() => onSelect(item.key)}
        style={{
          flex: 1,
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'center',
          gap: spacing.sm - 2,
          paddingVertical: 9,
          paddingHorizontal: spacing.lg - 2,
          borderRadius: radius.pill,
          backgroundColor: floating && active ? palette.navActiveBg : 'transparent',
        }}
      >
        <View
          style={{
            width: 8,
            height: 8,
            borderRadius: 4,
            backgroundColor: active
              ? floating
                ? palette.navActiveText
                : palette.accent
              : floating
                ? palette.navInactiveText
                : palette.textFaint,
          }}
        />
        <AppText
          variant={active ? 'navLabel' : 'bodySmall'}
          style={{
            fontSize: 11.5,
            color: active
              ? floating
                ? palette.navActiveText
                : palette.text
              : floating
                ? palette.navInactiveText
                : palette.textFaint,
          }}
        >
          {item.label}
        </AppText>
      </Pressable>
    );
  });

  if (floating) {
    const pillFill = withAlpha(palette.navBg, theme.alpha.navBar);
    return (
      <View
        pointerEvents="box-none"
        style={{
          position: 'absolute',
          left: layout.navInset,
          right: layout.navInset,
          bottom: layout.navInset + insets.bottom * 0.5,
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
        }}
      >
        <View style={{ flex: 1, borderRadius: radius.pill, overflow: 'hidden', ...elevation.nav }}>
          {blurEffects ? (
            <BlurView
              intensity={40}
              // The pill is inverse-ink in both themes, so the blur tint does
              // not flip with the colour scheme.
              tint="dark"
              // Android needs the opt-in method for a real blur; without it the
              // view falls back to a flat translucent fill.
              experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                padding: 6,
                backgroundColor: withAlpha(palette.navBg, 0.72),
              }}
            >
              {tabs}
            </BlurView>
          ) : (
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                padding: 6,
                backgroundColor: pillFill,
              }}
            >
              {tabs}
            </View>
          )}
        </View>
        {fab}
      </View>
    );
  }

  return (
    <View
      pointerEvents="box-none"
      style={{ position: 'absolute', left: 0, right: 0, bottom: 0 }}
    >
      <View
        pointerEvents="box-none"
        style={{ alignItems: 'flex-end', paddingRight: layout.navInset, marginBottom: spacing.md }}
      >
        {fab}
      </View>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          paddingTop: spacing.sm,
          paddingBottom: spacing.sm + insets.bottom,
          paddingHorizontal: spacing.sm,
          backgroundColor: palette.surface,
          borderTopWidth: 1,
          borderTopColor: palette.border,
        }}
      >
        {tabs}
      </View>
    </View>
  );
}
