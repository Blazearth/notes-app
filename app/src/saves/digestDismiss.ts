/**
 * Whether the user dismissed the Weekly digest card for its current week.
 *
 * A plain AsyncStorage read/write, not the local-first store: this is a
 * client-only UI preference the server never needs to see, the same
 * reasoning that keeps `openAppWhenSaving` local. Keyed off
 * `DigestResponse.weekStart` rather than a client-computed week boundary or
 * an expiry timer — a digest for a new week carries a new `weekStart`, so
 * comparing the dismissed marker against it is the whole "reappears when a
 * new week begins" rule.
 *
 * Its own dedicated AsyncStorage key, the same shape `SessionProvider` and
 * `PreferencesProvider` each use for their own single concern — a dismiss
 * flag is neither a session nor a Settings-surfaced preference.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useCallback, useEffect, useState } from 'react';

const DISMISSED_WEEK_KEY = 'weavr.digest.dismissedWeek';

/**
 * `hydrated` gates the caller's render the same way
 * `PreferencesProvider.hydrated` does, so a digest the user already
 * dismissed does not flash on screen for one frame before disappearing.
 */
export function useDigestDismissed(weekStart: string | undefined): {
  dismissed: boolean;
  dismiss: () => void;
  hydrated: boolean;
} {
  const [dismissedWeek, setDismissedWeek] = useState<string | null>(null);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    let cancelled = false;
    AsyncStorage.getItem(DISMISSED_WEEK_KEY)
      .then((raw) => {
        if (!cancelled) setDismissedWeek(raw);
      })
      .catch(() => {
        // A corrupt or unreadable value is not worth failing over — falls
        // through to "not dismissed," the same as never having dismissed it.
      })
      .finally(() => {
        if (!cancelled) setHydrated(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const dismiss = useCallback(() => {
    if (!weekStart) return;
    setDismissedWeek(weekStart);
    AsyncStorage.setItem(DISMISSED_WEEK_KEY, weekStart).catch(() => {});
  }, [weekStart]);

  return { dismissed: !!weekStart && dismissedWeek === weekStart, dismiss, hydrated };
}
