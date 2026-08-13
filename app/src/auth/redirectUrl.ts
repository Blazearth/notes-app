import * as Linking from 'expo-linking';

/**
 * Where Supabase should send a user back to after they tap an email
 * verification (or password-reset) link, landing on `AuthCallbackScreen`.
 *
 * `Linking.createURL` is what makes this environment-specific without a
 * branch or an env var: it resolves the app's own `scheme` ("weavr") in a
 * native build — dev client and production standalone alike, since this
 * project requires an EAS dev client rather than Expo Go and both use the
 * real scheme — and resolves to the page's own origin on web, so a local
 * `expo start --web` naturally gets `http://localhost:<port>/auth/callback`
 * and a hosted deployment would get that host instead. Nothing here should
 * ever be replaced with a literal `localhost`.
 *
 * This value alone is not sufficient — Supabase only honours an
 * `emailRedirectTo` that matches an entry in the project's Auth ->
 * URL Configuration -> Redirect URLs allow list. An unlisted value is
 * silently replaced with the dashboard's Site URL, which is how a
 * misconfigured project ends up sending users to `localhost` regardless of
 * what the app requests. See `app/README.md` for the exact values to add.
 */
export function getAuthCallbackUrl(): string {
  return Linking.createURL('/auth/callback');
}
