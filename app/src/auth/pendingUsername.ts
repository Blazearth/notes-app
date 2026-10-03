/**
 * A username chosen at sign-up that could not be registered yet.
 *
 * With email confirmation on (this project's setting), `signUp` returns no
 * session, so `PATCH /v1/me/username` cannot be authorised at that moment — it
 * failed with `unauthorized` and the sign-up screen reported it as "already
 * taken". The name is held here instead and registered by `SessionProvider`
 * the first time that account has a session, whether that is a sign-in or the
 * confirmation link's callback.
 *
 * Keyed by email, so confirming one account cannot claim a name another
 * sign-up on this device chose.
 */
import AsyncStorage from '@react-native-async-storage/async-storage';

const KEY = 'weavr.pendingUsername';

interface Pending {
  email: string;
  username: string;
}

export async function savePendingUsername(email: string, username: string): Promise<void> {
  const value: Pending = { email: email.trim().toLowerCase(), username };
  await AsyncStorage.setItem(KEY, JSON.stringify(value)).catch(() => {});
}

/** The pending name for this email, if any. */
export async function readPendingUsername(email: string | null | undefined): Promise<string | null> {
  if (!email) return null;
  try {
    const raw = await AsyncStorage.getItem(KEY);
    if (!raw) return null;
    const pending = JSON.parse(raw) as Pending;
    return pending.email === email.trim().toLowerCase() ? pending.username : null;
  } catch {
    return null;
  }
}

export async function clearPendingUsername(): Promise<void> {
  await AsyncStorage.removeItem(KEY).catch(() => {});
}
