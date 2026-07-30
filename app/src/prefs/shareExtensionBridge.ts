/**
 * Seam for mirroring preferences the iOS share extension needs.
 *
 * The share extension is a separate process and cannot read AsyncStorage, so
 * `openAppWhenSaving` has to be written into the App Group
 * (`UserDefaults(suiteName: "group.com.weavr.app")`) *at the moment it changes*
 * — see `app/README.md`.
 *
 * That write needs a native module, which does not exist yet: there is no
 * share extension in the project. This function is deliberately a no-op until
 * one lands, so the call site is already in the right place and the mirroring
 * cannot be forgotten when the extension is written.
 */
export async function mirrorSharedPreference(key: string, value: unknown): Promise<void> {
  if (__DEV__) {
    // eslint-disable-next-line no-console
    console.log(`[weavr] share-extension mirror pending: ${key} = ${String(value)}`);
  }
}
