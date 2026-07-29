# Weavr — mobile app

Not scaffolded yet. Phase 1's mobile half is an Expo app with an **EAS dev
client** from day one: `expo-share-extension` and `react-native-purchases` are
native modules, so **Expo Go will never work**.

Identifiers, fixed alongside the backend package name:

| | |
|---|---|
| Expo slug | `weavr` |
| iOS bundle | `com.weavr.app` |
| Android package | `com.weavr.app` |
| App Group | `group.com.weavr.app` |
| Keychain access group | `com.weavr.shared` |

The App Group and Keychain access group matter early: the share extension is a
separate process that cannot reach the app's AsyncStorage. The auth token
crosses via the **Keychain access group**, and the *Open app when saving* toggle
crosses via **App Group `UserDefaults`**, written whenever it changes in the app.

Store the **refresh** token, not just the access token. Supabase access tokens
are short-lived and the extension will often run with an expired one — decide
this before writing the extension; retrofitting it is painful.

See [docs/implementation-plan.md](../docs/implementation-plan.md), Phase 1–2.
