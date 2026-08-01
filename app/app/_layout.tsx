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
        <Stack.Screen name="shopping-list" options={{ animation: 'slide_from_right' }} />
        {/* Search reads as a layer over the feed rather than a place you
            travel to, so it fades in where the others slide. */}
        <Stack.Screen name="search" options={{ animation: 'fade' }} />
        <Stack.Screen
          name="capture"
          options={{
            presentation: 'transparentModal',
            animation: 'fade',
          }}
        />
      </Stack>
    </>
  );
}

function SplashGate({ fontsReady }: { fontsReady: boolean }) {
  const { hydrated: prefsReady } = usePreferences();
  const { hydrated: sessionReady } = useSession();
  const ready = fontsReady && prefsReady && sessionReady;

  // Hold the splash until the stored preferences, the stored session and the
  // Sora faces are all in. Releasing early renders the first frame in the wrong
  // theme, or flashes sign-in at an already-signed-in user.
  useEffect(() => {
    if (ready) SplashScreen.hideAsync().catch(() => {});
  }, [ready]);

  if (!ready) return null;

  return (
    <ThemeProvider>
      <SavesProvider>
        <Routes />
      </SavesProvider>
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
  if (MISSING_CONFIG.length > 0) {
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
