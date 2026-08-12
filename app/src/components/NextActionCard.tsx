/**
 * The single "what should I do next" nudge — one grounded suggestion from
 * `@/collections/nextAction`, never a generated one. The card states the
 * pick and its context up front; the reasoning behind it is one tap away
 * behind "Why?" rather than always on screen, so the moment reads as a real
 * suggestion rather than an ambient AI banner repeated on every screen.
 *
 * One component, two callers: a leaf `CollectionDetailScreen` renders it
 * above its own entity list, and `HomeScreen` renders the single
 * highest-ranked one across the whole tree under "Today". Both just supply a
 * `NextAction` and a primary-button handler — the card has no opinion about
 * where the button navigates.
 */
import React, { useState } from 'react';
import { View } from 'react-native';

import type { NextAction } from '@/collections/nextAction';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

export function NextActionCard({
  action,
  label = 'Next up',
  onPrimary,
}: {
  action: NextAction;
  /** "Next up" on Home (cross-type); a leaf screen names its own kind of pick — "Pick one", "Ready to start". */
  label?: string;
  onPrimary: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const [showWhy, setShowWhy] = useState(false);

  return (
    <Card variant="accent" radius={radius.lg} style={{ marginBottom: spacing.xxl - 2 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginBottom: spacing.sm }}>
        <Glyph name="activity" size={13} weight={2} color={palette.onAccentContainer} />
        <AppText variant="sectionLabel" tone="onAccentContainer">
          {label}
        </AppText>
      </View>

      <AppText variant="heading" tone="onAccentContainer" numberOfLines={2} style={{ marginBottom: spacing.xs }}>
        {action.headline}
      </AppText>
      <AppText tone="onAccentContainer" style={{ marginBottom: spacing.md }}>
        {action.detail}
      </AppText>

      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.lg, flexWrap: 'wrap' }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={action.actionLabel}
          onPress={onPrimary}
          haptic="medium"
          weight="card"
          style={{
            paddingVertical: spacing.sm,
            paddingHorizontal: spacing.lg,
            borderRadius: 100,
            backgroundColor: palette.background,
          }}
        >
          <AppText variant="label">{action.actionLabel}</AppText>
        </Touchable>

        {action.reasons.length > 0 ? (
          <Touchable
            accessibilityRole="button"
            accessibilityState={{ expanded: showWhy }}
            accessibilityLabel={showWhy ? 'Hide why' : `Why ${action.headline}?`}
            onPress={() => setShowWhy((v) => !v)}
            haptic="selection"
            style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}
          >
            <AppText variant="label" tone="onAccentContainer">
              {showWhy ? 'Hide why' : 'Why?'}
            </AppText>
            <Glyph
              name="chevron"
              size={11}
              weight={2}
              color={palette.onAccentContainer}
              style={{ transform: [{ rotate: showWhy ? '90deg' : '-90deg' }] }}
            />
          </Touchable>
        ) : null}
      </View>

      {showWhy ? (
        <View style={{ marginTop: spacing.md, gap: spacing.xs }}>
          {action.reasons.map((reason) => (
            <View key={reason} style={{ flexDirection: 'row', gap: spacing.xs, alignItems: 'flex-start' }}>
              <Glyph name="check" size={12} weight={2} color={palette.onAccentContainer} style={{ marginTop: 2 }} />
              <AppText variant="caption" tone="onAccentContainer" style={{ flex: 1 }}>
                {reason}
              </AppText>
            </View>
          ))}
        </View>
      ) : null}
    </Card>
  );
}
