import AsyncStorage from '@react-native-async-storage/async-storage';
import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { mirrorSharedPreference } from './shareExtensionBridge';
import { mirrorOpenAppWhenSaving } from '@/share/nativeShareConfig';
import { DEFAULT_PREFERENCES, PREFERENCES_STORAGE_KEY, type Preferences } from './types';

interface PreferencesContextValue {
  prefs: Preferences;
  /** False until the stored blob has been read, so the UI can avoid a flash. */
  hydrated: boolean;
  setPreference: <K extends keyof Preferences>(key: K, value: Preferences[K]) => void;
  resetPreferences: () => void;
}

const PreferencesContext = createContext<PreferencesContextValue | null>(null);

/** Drop unknown keys and keep defaults for anything missing or mistyped. */
function merge(stored: unknown): Preferences {
  if (!stored || typeof stored !== 'object') return DEFAULT_PREFERENCES;
  const next = { ...DEFAULT_PREFERENCES };
  for (const key of Object.keys(DEFAULT_PREFERENCES) as (keyof Preferences)[]) {
    const value = (stored as Record<string, unknown>)[key];
    if (typeof value === typeof DEFAULT_PREFERENCES[key]) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (next as any)[key] = value;
    }
  }
  return next;
}

export function PreferencesProvider({ children }: { children: React.ReactNode }) {
  const [prefs, setPrefs] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [hydrated, setHydrated] = useState(false);
  // Persist writes are fire-and-forget; this keeps the newest value winning
  // even if two setters land in the same tick.
  const latest = useRef(prefs);

  useEffect(() => {
    let cancelled = false;
    AsyncStorage.getItem(PREFERENCES_STORAGE_KEY)
      .then((raw) => {
        if (cancelled) return;
        if (raw) {
          const parsed = merge(JSON.parse(raw));
          latest.current = parsed;
          setPrefs(parsed);
        }
      })
      .catch(() => {
        // A corrupt or unreadable blob is not worth failing the app over —
        // fall through to defaults and let the next write repair it.
      })
      .finally(() => {
        if (!cancelled) {
          setHydrated(true);
          // Mirror the resolved value (stored or default) so Android's share
          // Activity reflects it from cold start, not just from the next toggle.
          mirrorOpenAppWhenSaving(latest.current.openAppWhenSaving);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const persist = useCallback((next: Preferences) => {
    latest.current = next;
    setPrefs(next);
    AsyncStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(next)).catch(() => {});
  }, []);

  const setPreference = useCallback(
    <K extends keyof Preferences>(key: K, value: Preferences[K]) => {
      persist({ ...latest.current, [key]: value });
      if (key === 'openAppWhenSaving') {
        void mirrorSharedPreference(key, value);
        mirrorOpenAppWhenSaving(value as boolean);
      }
    },
    [persist],
  );

  const resetPreferences = useCallback(() => persist(DEFAULT_PREFERENCES), [persist]);

  const value = useMemo(
    () => ({ prefs, hydrated, setPreference, resetPreferences }),
    [prefs, hydrated, setPreference, resetPreferences],
  );

  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences(): PreferencesContextValue {
  const ctx = useContext(PreferencesContext);
  if (!ctx) throw new Error('usePreferences must be used inside <PreferencesProvider>');
  return ctx;
}
