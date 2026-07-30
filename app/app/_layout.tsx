import { Sora_500Medium, Sora_600SemiBold, Sora_700Bold, useFonts } from '@expo-google-fonts/sora';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import React, { useEffect } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { PreferencesProvider, usePreferences } from '@/prefs/PreferencesProvider';
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
        <Stack.Screen name="appearance" options={{ animation: 'slide_from_right' }} />
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
  const { hydrated } = usePreferences();

  // Hold the splash until both the stored preferences and the Sora faces are
  // in, otherwise the first frame renders in the wrong theme and font.
  useEffect(() => {
    if (fontsReady && hydrated) SplashScreen.hideAsync().catch(() => {});
  }, [fontsReady, hydrated]);

  if (!fontsReady || !hydrated) return null;

  return (
    <ThemeProvider>
      <Routes />
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

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <PreferencesProvider>
          <SplashGate fontsReady={fontsReady} />
        </PreferencesProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
