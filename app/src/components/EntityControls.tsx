import React from 'react';
import { View } from 'react-native';

import type { EntityStatus } from '@/collections/entityStatus';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

/**
 * The status control for a watchlist entity — Want to watch → Watching →
 * Watched, one tap per step.
 *
 * A cycling pill rather than a picker or a menu, because the states are
 * ordered and the overwhelmingly common move is forward by one. A menu makes
 * the cheap action cost two taps to save the rare backward one; the pill
 * makes the common case a single tap and the rare one three, which is the
 * right trade for something the user does to every title in a list.
 *
 * `entityKey` goes into the accessibility label because a list of these is
 * otherwise a row of identically-labelled controls, indistinguishable to a
 * screen reader and to a CDP probe.
 */
export function StatusPill({
  status,
  name,
  onPress,
}: {
  status: EntityStatus;
  name: string;
  onPress: () => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const active = status.done;

  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={`${name}: ${status.label}. Tap to change.`}
      onPress={onPress}
      haptic="selection"
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.xs,
        paddingVertical: 4,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.pill,
        borderWidth: 1,
        borderColor: active ? 'transparent' : palette.border,
        backgroundColor: active ? `${palette.accent}22` : 'transparent',
      }}
    >
      <Glyph
        name={active ? 'checkSquare' : 'ring'}
        size={12}
        weight={2}
        color={active ? palette.accent : palette.textFaint}
      />
      <AppText variant="caption" tone={active ? 'accent' : 'muted'} style={{ fontSize: 11 }}>
        {status.label}
      </AppText>
    </Touchable>
  );
}

/**
 * A 1–5 star rating.
 *
 * Tapping the star already selected clears the rating rather than re-setting
 * it — otherwise a mis-tap is permanent, since there is no other way to say
 * "actually, no rating". The same reason a radio group needs a "none".
 */
export function StarRating({
  rating,
  name,
  onRate,
  size = 14,
}: {
  rating: number;
  name: string;
  onRate: (rating: number) => void;
  size?: number;
}) {
  const { palette } = useTheme();
  return (
    <View style={{ flexDirection: 'row', gap: 2 }}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Touchable
          key={n}
          accessibilityRole="button"
          accessibilityLabel={
            n === rating ? `${name}: clear rating` : `${name}: rate ${n} of 5`
          }
          onPress={() => onRate(n === rating ? 0 : n)}
          haptic="selection"
        >
          <Glyph name="star" size={size} color={n <= rating ? palette.accent : palette.textFaint} />
        </Touchable>
      ))}
    </View>
  );
}
