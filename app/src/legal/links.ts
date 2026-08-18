/**
 * Where the hosted legal documents live.
 *
 * These are **hosted web pages, not in-app screens**, and deliberately so:
 * Google Play requires a publicly reachable privacy policy URL that works
 * without installing the app, and duplicating the text into a React Native
 * screen would create a second copy that drifts from the published one the
 * first time either is edited. The source of the published page is
 * `legal/privacy.html` at the repo root — see `legal/README.md` for how it is
 * hosted.
 *
 * `PRIVACY_POLICY_URL` must be a real, live URL before the app ships. Until it
 * is set, {@link hasLegalUrl} is false and the caller hides the row rather than
 * offering a link that opens nothing — the same rule the rest of the app
 * follows for surfaces the server cannot yet back.
 */

/** The published privacy policy. Replace before release. */
export const PRIVACY_POLICY_URL = 'https://privacy.ryonkai-devs.workers.dev/';

/**
 * Play's App content → Data deletion field also wants a URL. The policy's own
 * deletion section is written to serve as that page, so this points at its
 * anchor rather than at a second document that would have to be kept in sync.
 */
export const DATA_DELETION_URL = PRIVACY_POLICY_URL
  ? `${PRIVACY_POLICY_URL}#account-deletion`
  : '';

/** True when a URL has actually been configured. */
export function hasLegalUrl(url: string): boolean {
  return url.trim().length > 0;
}
