// Must come before anything that touches URL/URLSearchParams. Hermes ships an
// incomplete `URL`, and both the Supabase client and our own `saveTitle` parse
// URLs — without this, hostname comes back empty rather than throwing.
import 'react-native-url-polyfill/auto';

import { Sora_500Medium, Sora_600SemiBold, Sora_700Bold, useFonts } from '@expo-google-fonts/sora';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import React, { useEffect } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { MISSING_CONFIG } from '@/api/config';
import { SessionProvider, useSession } from '@/auth/SessionProvider';
import { USE_MOCK_DATA } from '@/data/config';
import { openStore } from '@/local';
import { SyncProvider } from '@/local/SyncProvider';
import { PreferencesProvider, usePreferences } from '@/prefs/PreferencesProvider';
import { SavesProvider } from '@/saves/SavesProvider';
import { ConfigErrorScreen } from '@/screens/ConfigErrorScreen';
import { ThemeProvider, useTheme } from '@/theme/ThemeProvider';

SplashScreen.preventAutoHideAsync().catch(() => {});

/**
 * Inside the theme so the native stack background and the status-bar icons
 * follow the palette. Without the `contentStyle` background a theme switch
 * flashes white between screens.
 */
function Routes() {
  const { palette } = useTheme();
  return (
    <>
      <StatusBar style={palette.isDark ? 'light' : 'dark'} />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: palette.background },
        }}
      >
        <Stack.Screen name="index" />
        <Stack.Screen name="sign-in" options={{ animation: 'fade' }} />
        <Stack.Screen name="appearance" options={{ animation: 'slide_from_right' }} />
        {/* Settings does not slide in — it grows out of the gear that opened it
            (`MorphPresentation`). That needs three things from the stack, and
            all three are load-bearing: a transparent presentation so Home stays
            visible and can recede behind it, no stack animation of its own to
            fight or double up with the morph, and no back gesture — a swipe
            would pop the route instantly and skip the collapse, which is the
            half of the transition that has to mirror the other. */}
        <Stack.Screen
          name="settings"
          options={{
            presentation: 'transparentModal',
            animation: 'none',
            gestureEnabled: false,
            // Overrides the shared opaque `contentStyle` below; without this the
            // stack paints the page background behind the morph and Home is gone
            // before the first frame.
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
        <Stack.Screen name="save/[id]" options={{ animation: 'slide_from_right' }} />
        <Stack.Screen name="space/[id]" options={{ animation: 'slide_from_right' }} />
        {/* Create/Join Spaces — same `transparentModal` + `animation: 'none'`
            shape as `capture`, and for the same reason: `Sheet` drives its own
            entrance from one shared value, so a stack animation on top would
            fade the backdrop in twice on two curves that don't match, and a
            non-transparent presentation would paint over the Spaces list this
            is meant to sit above. */}
        <Stack.Screen
          name="space/create"
          options={{
            presentation: 'transparentModal',
            animation: 'none',
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
        <Stack.Screen
          name="space/join"
          options={{
            presentation: 'transparentModal',
            animation: 'none',
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
        {/* Groups nest, so this route pushes onto itself. The slide is what
            makes going a level deeper legible as travel rather than as the
            screen's contents being swapped underneath you. */}
        <Stack.Screen name="group/[id]" options={{ animation: 'slide_from_right' }} />
        {/* K3: the Library's collections-first top level for entity-bearing
            types. Not nested like group/[id] — a collection's entities are
            leaves (their detail is the in-screen sheet, not another route),
            so there is nothing to push onto itself. */}
        <Stack.Screen name="collection/[type]" options={{ animation: 'slide_from_right' }} />
        {/* Running a collection's merged exercises — "Start workout" on a
            training split. Its own route rather than a modal on the collection
            screen, so the back gesture ends the session cleanly with the
            collection still underneath. */}
        <Stack.Screen name="session/[nodeId]" options={{ animation: 'slide_from_right' }} />
        {/* K5: the local-compute alternative to AI workout synthesis — a
            side-by-side compare, reached by multi-select from the workout
            group screen. */}
        <Stack.Screen name="compare-workouts" options={{ animation: 'slide_from_right' }} />
        <Stack.Screen name="shopping-list" options={{ animation: 'slide_from_right' }} />
        {/* Search reads as a layer over the feed rather than a place you
            travel to, so it fades in where the others slide. */}
        <Stack.Screen name="search" options={{ animation: 'fade' }} />
        {/* Same three requirements as `settings`, and for the same reasons.
            `transparentModal` alone is not enough: the shared `contentStyle`
            below is opaque, so without the override the stack paints the page
            background across the whole route and Home is gone — which is
            exactly the "blank background behind the FAB menu" this fixes. The
            blur has to have something to blur.

            `animation: 'none'` because the sheet drives its own entrance from
            one shared value. A stack fade on top of that fades the backdrop in
            twice, on two curves that do not match. */}
        <Stack.Screen
          name="capture"
          options={{
            presentation: 'transparentModal',
            animation: 'none',
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
      </Stack>
    </>
  );
}

function SplashGate({ fontsReady }: { fontsReady: boolean }) {
  const { hydrated: prefsReady } = usePreferences();
  const { hydrated: sessionReady } = useSession();

  // The fourth gate: the local store. Screens read from it during their first
  // render (see `useLive`), so it has to be open before any of them mount —
  // and it is the *reason* the first frame can have content at all rather than
  // a spinner, which is what makes it worth blocking on. `openStore` never
  // rejects: a storage failure degrades to an empty cache that the sync refills
  // (`@/local/index`), never to a splash that will not lift.
  const [storeReady, setStoreReady] = React.useState(false);
  useEffect(() => {
    let cancelled = false;
    void openStore().then(() => {
      if (!cancelled) setStoreReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const ready = fontsReady && prefsReady && sessionReady && storeReady;

  // Hold the splash until the stored preferences, the stored session, the local
  // store and the Sora faces are all in. Releasing early renders the first frame
  // in the wrong theme, or flashes sign-in at an already-signed-in user.
  useEffect(() => {
    if (ready) SplashScreen.hideAsync().catch(() => {});
  }, [ready]);

  if (!ready) return null;

  return (
    <ThemeProvider>
      {/* Between the session and everything that reads data: it wipes a store
          belonging to a different account before a screen can render it, and
          starts the background sync that fills the store the screens read. */}
      <SyncProvider>
        <SavesProvider>
          <Routes />
        </SavesProvider>
      </SyncProvider>
    </ThemeProvider>
  );
}

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    Sora_500Medium,
    Sora_600SemiBold,
    Sora_700Bold,
  });

  // A font failure should degrade to the system face, not hang the splash.
  const fontsReady = fontsLoaded || fontError != null;

  // Before anything else: without configuration the Supabase client and the API
  // client are both inert, so say so plainly instead of failing at the first
  // request with a network error that looks like a server problem.
  //
  // Skipped entirely when mocking — the whole point of that mode is that none
  // of these three variables is needed, so demanding them would make "runs with
  // no backend" false at the first frame.
  if (!USE_MOCK_DATA && MISSING_CONFIG.length > 0) {
    SplashScreen.hideAsync().catch(() => {});
    return (
      <SafeAreaProvider>
        <ConfigErrorScreen missing={MISSING_CONFIG} />
      </SafeAreaProvider>
    );
  }

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <PreferencesProvider>
          <SessionProvider>
            <SplashGate fontsReady={fontsReady} />
          </SessionProvider>
        </PreferencesProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
