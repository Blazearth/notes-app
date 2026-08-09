import React, { useEffect, useRef, useState } from 'react';
import { Pressable, View, type LayoutChangeEvent } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';

import { useHaptic } from '@/motion/haptics';
import { Spring } from '@/theme/motion';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

export interface SegmentedOption<T extends string> {
  value: T;
  label: string;
}

export interface SegmentedProps<T extends string> {
  options: SegmentedOption<T>[];
  value: T;
  onChange: (value: T) => void;
}

/**
 * The Spaces tab strip (Overview / Sources / People / Activity), reused in Settings.
 *
 * The selected fill is a single thumb that *travels* to the tapped segment
 * rather than a background that switches on whichever segment is active. That
 * distinction is the whole point: a travelling thumb shows which direction you
 * moved through the set, so the control explains the change instead of merely
 * reflecting it.
 */
export function Segmented<T extends string>({ options, value, onChange }: SegmentedProps<T>) {
  const { palette, radius, spacing } = useTheme();
  const haptic = useHaptic();
  const reduced = useReducedMotion();

  const pad = spacing.xs;
  const gap = spacing.xs + 2;
  const count = options.length;

  const [width, setWidth] = useState(0);
  // Width has to be measured: the segments are `flex: 1`, so their size is not
  // known until layout, and the thumb has to match it exactly.
  const segment = width > 0 ? (width - pad * 2 - gap * (count - 1)) / count : 0;

  const index = Math.max(
    0,
    options.findIndex((option) => option.value === value),
  );

  const x = useSharedValue(0);
  const settled = useRef(false);

  useEffect(() => {
    if (segment <= 0) return;
    const target = pad + index * (segment + gap);
    // The first placement jumps. Springing from x=0 on mount would animate the
    // thumb in from the left edge every time the screen opens, which reads as
    // a loading artefact rather than as feedback.
    if (settled.current && !reduced) {
      x.value = withSpring(target, Spring.travel);
    } else {
      x.value = target;
      settled.current = true;
    }
  }, [index, segment, pad, gap, reduced, x]);

  const thumbStyle = useAnimatedStyle(() => ({ transform: [{ translateX: x.value }] }));

  const onLayout = (event: LayoutChangeEvent) => setWidth(event.nativeEvent.layout.width);

  return (
    <View
      onLayout={onLayout}
      style={{
        flexDirection: 'row',
        gap,
        backgroundColor: palette.surfaceMuted,
        borderRadius: radius.md - 2,
        padding: pad,
      }}
    >
      {segment > 0 ? (
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              left: 0,
              top: pad,
              bottom: pad,
              width: segment,
              borderRadius: radius.sm - 1,
              backgroundColor: palette.surface,
            },
            thumbStyle,
          ]}
        />
      ) : null}

      {options.map((option) => {
        const active = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="tab"
            // Explicit, even though the child text would usually be announced:
            // React Native Web emits no `aria-selected` for `accessibilityState`
            // (see docs/testing.md), so the label is the only stable handle a
            // screen reader — or a probe — has on an individual tab.
            accessibilityLabel={option.label}
            accessibilityState={{ selected: active }}
            onPress={() => {
              if (active) return;
              haptic('selection');
              onChange(option.value);
            }}
            // No press scale here: the thumb is already the feedback, and a
            // label that shrinks while the fill slides under it is two answers
            // to one question.
            style={{
              flex: 1,
              alignItems: 'center',
              paddingVertical: spacing.sm,
            }}
          >
            <AppText
              variant={active ? 'label' : 'bodySmall'}
              tone={active ? 'default' : 'muted'}
              style={{ fontSize: 12 }}
            >
              {option.label}
            </AppText>
          </Pressable>
        );
      })}
    </View>
  );
}
