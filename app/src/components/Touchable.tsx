import React, { useCallback } from 'react';
import {
  Pressable,
  type GestureResponderEvent,
  type PressableProps,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';

import { useHaptic, type HapticTone } from '@/motion/haptics';
import { PRESS_DIM, PressDepth, Spring, type PressWeight } from '@/theme/motion';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

export interface TouchableProps extends Omit<PressableProps, 'style'> {
  style?: StyleProp<ViewStyle>;
  /** How far the surface dips. Large surfaces move least — see `PressDepth`. */
  weight?: PressWeight;
  /**
   * Tactile response on press-*in*, so the feedback lands with the finger
   * rather than after the handler. Pass `null` for surfaces tapped in rapid
   * succession, where a buzz per tap becomes noise.
   */
  haptic?: HapticTone | null;
  /**
   * Resting opacity, for disabled and de-emphasised states.
   *
   * This exists because the press dim is an animated `opacity`, and an animated
   * style always wins over the static one beside it — so `opacity` passed in
   * `style` would be silently dropped, rendering a disabled control at full
   * strength. Routing it here lets the two compose instead of collide.
   */
  baseOpacity?: number;
  children?: React.ReactNode;
}

/**
 * The single press surface for the whole app.
 *
 * Every tappable thing routes through here so press feedback is identical
 * everywhere — the same spring, the same dip, the same tactile tone. A bare
 * `Pressable` with `pressed && { opacity }` is the alternative, and it steps
 * instantly between two states, which is precisely what makes an interface
 * feel like a web page rather than an app.
 *
 * Driven by a shared value rather than Pressable's `pressed` flag: `pressed`
 * is React state, so releasing mid-spring would cancel the animation on a
 * re-render. The shared value lets press-out retarget the *running* spring,
 * so a quick tap reads as one gesture instead of a snap back to rest.
 */
export function Touchable({
  style,
  weight = 'control',
  haptic = 'light',
  baseOpacity = 1,
  onPressIn,
  onPressOut,
  disabled,
  children,
  ...rest
}: TouchableProps) {
  const press = useSharedValue(0);
  const fireHaptic = useHaptic();

  // Honours the OS "Reduce Motion" switch. Scale is suppressed but the opacity
  // dip is kept: the setting asks for less movement, not for the loss of any
  // signal that a tap registered.
  const reduced = useReducedMotion();
  const depth = PressDepth[weight];

  const animatedStyle = useAnimatedStyle(() => ({
    transform: [{ scale: reduced ? 1 : 1 - press.value * (1 - depth) }],
    opacity: baseOpacity * (1 - press.value * PRESS_DIM),
  }));

  const handlePressIn = useCallback(
    (event: GestureResponderEvent) => {
      press.value = withSpring(1, Spring.press);
      if (haptic) fireHaptic(haptic);
      onPressIn?.(event);
    },
    [press, haptic, fireHaptic, onPressIn],
  );

  const handlePressOut = useCallback(
    (event: GestureResponderEvent) => {
      press.value = withSpring(0, Spring.press);
      onPressOut?.(event);
    },
    [press, onPressOut],
  );

  return (
    <AnimatedPressable
      {...rest}
      disabled={disabled}
      onPressIn={disabled ? undefined : handlePressIn}
      onPressOut={disabled ? undefined : handlePressOut}
      style={[style, animatedStyle]}
    >
      {children}
    </AnimatedPressable>
  );
}
