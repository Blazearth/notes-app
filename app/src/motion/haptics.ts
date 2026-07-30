import * as Haptics from 'expo-haptics';
import { useCallback } from 'react';
import { Platform } from 'react-native';

import { usePreferences } from '@/prefs/PreferencesProvider';

/**
 * Tactile feedback, mapped to intent rather than to an API constant.
 *
 * Call sites say what *happened* ('selection', 'success'), not how hard to
 * buzz, so the intensity curve can be retuned in one place. The mapping below
 * is the whole policy: taps are light, commitments are medium, outcomes get
 * a notification pattern.
 */
export type HapticTone =
  /** Repeated, low-stakes taps — a chip, a tab, a row. */
  | 'light'
  /** A commitment — opening capture, submitting, toggling a setting. */
  | 'medium'
  /** Moving between discrete options in a group. */
  | 'selection'
  /** An action completed. */
  | 'success'
  /** An action failed. */
  | 'error';

/**
 * Web has no haptics API worth using, and the simulators no-op — so gate here
 * rather than letting every call site pay for a promise that resolves to
 * nothing.
 */
const SUPPORTED = Platform.OS === 'ios' || Platform.OS === 'android';

function fire(tone: HapticTone): Promise<void> {
  switch (tone) {
    case 'light':
      return Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    case 'medium':
      return Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    case 'selection':
      return Haptics.selectionAsync();
    case 'success':
      return Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    case 'error':
      return Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
  }
}

/**
 * Returns a fire-and-forget haptic trigger that respects the user's preference.
 *
 * Failures are swallowed: a device with a broken or busy taptic engine should
 * lose the buzz, not the interaction it was attached to.
 */
export function useHaptic(): (tone: HapticTone) => void {
  const { prefs } = usePreferences();
  const enabled = prefs.haptics;

  return useCallback(
    (tone: HapticTone) => {
      if (!SUPPORTED || !enabled) return;
      fire(tone).catch(() => {});
    },
    [enabled],
  );
}
