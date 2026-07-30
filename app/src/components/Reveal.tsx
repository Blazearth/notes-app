import React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';

import { staggerDelay } from '@/theme/motion';

export interface RevealProps {
  /**
   * Position in the run of revealed blocks on this screen. Later blocks wait
   * longer, so the screen assembles top-down instead of appearing at once.
   */
  index?: number;
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
}

/**
 * The standard content entrance.
 *
 * Screens are built from a handful of stacked sections, and revealing them on
 * a stagger gives the eye an order to read them in — the alternative is a
 * finished screen appearing whole, which carries no hierarchy at all.
 *
 * Mount-only by design: `entering` fires once, so a section that re-renders on
 * new data does not re-animate. That is what keeps the Home feed from
 * flickering every time a save arrives.
 *
 * Reanimated's entering animations honour the OS "Reduce Motion" setting on
 * their own, so there is no branch for it here.
 */
export function Reveal({ index = 0, children, style }: RevealProps) {
  return (
    <Animated.View style={style} entering={FadeInDown.delay(staggerDelay(index)).duration(280)}>
      {children}
    </Animated.View>
  );
}
