import { BlurView } from 'expo-blur';
import { useRouter } from 'expo-router';
import React from 'react';
import { Platform, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { CAPTURE_OPTIONS, CAPTURE_SUBTITLE, type CaptureOption } from '@/data/sampleContent';
import { useTheme } from '@/theme/ThemeProvider';

const COLUMNS = 4;

function chunk<T>(items: T[], size: number): T[][] {
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += size) rows.push(items.slice(i, i + size));
  return rows;
}

function OptionTile({ option }: { option: CaptureOption }) {
  const { palette, radius, spacing, layout } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={option.label}
      style={({ pressed }) => [
        { flex: 1, alignItems: 'center', gap: spacing.sm },
        pressed && { opacity: 0.6 },
      ]}
    >
      <View
        style={{
          width: layout.minTouchTarget + 12,
          height: layout.minTouchTarget + 12,
          borderRadius: radius.lg,
          backgroundColor: palette.surfaceVariant,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Glyph name={option.glyph} size={20} weight={2} />
      </View>
      <AppText variant="caption" style={{ fontSize: 11, textAlign: 'center' }}>
        {option.label}
      </AppText>
    </Pressable>
  );
}

/**
 * Universal Capture — the FAB sheet.
 *
 * In-app capture is the *secondary* path: the primary one is the OS share sheet,
 * which never opens the app. This exists for content the user is holding rather
 * than viewing (a photo, a typed note, a link on the clipboard).
 */
export function CaptureSheet() {
  const { palette, radius, spacing, layout, elevation, alpha, blurEffects } = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();

  const rows = chunk(CAPTURE_OPTIONS, COLUMNS);

  return (
    <View style={{ flex: 1, justifyContent: 'flex-end' }}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Dismiss"
        onPress={() => router.back()}
        style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
      >
        {blurEffects ? (
          <BlurView
            intensity={18}
            tint={palette.isDark ? 'dark' : 'light'}
            experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
            style={{ flex: 1, backgroundColor: palette.scrim }}
          />
        ) : (
          <View style={{ flex: 1, backgroundColor: palette.scrim, opacity: alpha.scrim + 0.3 }} />
        )}
      </Pressable>

      <View
        style={{
          backgroundColor: palette.surface,
          borderTopLeftRadius: radius.xl,
          borderTopRightRadius: radius.xl,
          paddingTop: spacing.md,
          paddingHorizontal: layout.screenGutter,
          paddingBottom: spacing.xxl + insets.bottom,
          ...elevation.sheet,
        }}
      >
        <View
          style={{
            width: 36,
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.border,
            alignSelf: 'center',
            marginBottom: spacing.lg + 2,
          }}
        />
        <AppText variant="heading" style={{ marginBottom: spacing.xs }}>
          Add to Weavr
        </AppText>
        <AppText tone="muted" style={{ fontSize: 12.5, marginBottom: spacing.xl }}>
          {CAPTURE_SUBTITLE}
        </AppText>

        <View style={{ gap: spacing.md + 2 }}>
          {rows.map((row, index) => (
            <View key={index} style={{ flexDirection: 'row', gap: spacing.md + 2 }}>
              {row.map((option) => (
                <OptionTile key={option.id} option={option} />
              ))}
              {/* Keep the last row's columns aligned with the first. */}
              {Array.from({ length: COLUMNS - row.length }).map((_, i) => (
                <View key={`spacer-${i}`} style={{ flex: 1 }} />
              ))}
            </View>
          ))}
        </View>
      </View>
    </View>
  );
}
