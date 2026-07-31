import { Platform } from 'react-native';
import { File, Paths } from 'expo-file-system';

import { API_BASE_URL } from '@/api/config';

/**
 * Mirrors what the Android share Activity/WorkManager worker need into a
 * plain JSON file in the app's private files dir.
 *
 * `ShareReceiverActivity` and `ShareUploadWorker` (added by
 * `plugins/withAndroidShareReceiver.js`) run in the same process as the JS
 * runtime, just outside it, so there is no App-Group-style cross-process
 * boundary the way iOS's share extension has — `Paths.document` on Android
 * *is* `context.filesDir`, so writing here is enough for the native side to
 * read directly. See CLAUDE.md "Capture flow: silent by default".
 *
 * No-op on iOS: the share extension there needs a Keychain access group
 * instead, which does not exist yet (`shareExtensionBridge.ts`).
 */
const SHARE_CONFIG_FILENAME = 'weavr_share_config.json';

interface NativeShareConfig {
  accessToken: string | null;
  refreshToken: string | null;
  apiBaseUrl: string;
  openAppWhenSaving: boolean;
}

let current: NativeShareConfig = {
  accessToken: null,
  refreshToken: null,
  apiBaseUrl: API_BASE_URL,
  openAppWhenSaving: false,
};

function persist(): void {
  if (Platform.OS !== 'android') return;
  try {
    new File(Paths.document, SHARE_CONFIG_FILENAME).write(JSON.stringify(current));
  } catch {
    // Best-effort mirror — a stale file just means the next silent share
    // falls back to "sign in to Weavr" instead of uploading.
  }
}

/** Call whenever the Supabase session changes (sign-in, sign-out, refresh). */
export function mirrorShareSession(accessToken: string | null, refreshToken: string | null): void {
  current = { ...current, accessToken, refreshToken };
  persist();
}

/** Call on hydration and whenever the "open app when saving" toggle changes. */
export function mirrorOpenAppWhenSaving(openAppWhenSaving: boolean): void {
  current = { ...current, openAppWhenSaving };
  persist();
}
