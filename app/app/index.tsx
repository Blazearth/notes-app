import { useRouter } from 'expo-router';
import React, { useState } from 'react';
import { View } from 'react-native';

import { BottomNav, type NavItem } from '@/components/BottomNav';
import { HomeScreen } from '@/screens/HomeScreen';
import { LibraryScreen } from '@/screens/LibraryScreen';
import { SpacesScreen } from '@/screens/SpacesScreen';
import { useTheme } from '@/theme/ThemeProvider';

const TABS: NavItem[] = [
  { key: 'home', label: 'Home' },
  { key: 'library', label: 'Library' },
  { key: 'spaces', label: 'Spaces' },
];

/**
 * The tab shell.
 *
 * Home / Library / Spaces are kept mounted once visited rather than being three
 * routes, so switching tabs preserves scroll position — the three screens are
 * dense and losing your place in the Library is worse than the extra memory.
 * Tabs render lazily: nothing mounts until it is first opened.
 */
export default function TabShell() {
  const { palette } = useTheme();
  const router = useRouter();
  const [active, setActive] = useState('home');
  const [visited, setVisited] = useState<Record<string, boolean>>({ home: true });

  const select = (key: string) => {
    setVisited((prev) => (prev[key] ? prev : { ...prev, [key]: true }));
    setActive(key);
  };

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      {visited.home ? (
        <View style={{ flex: 1, display: active === 'home' ? 'flex' : 'none' }}>
          <HomeScreen />
        </View>
      ) : null}
      {visited.library ? (
        <View style={{ flex: 1, display: active === 'library' ? 'flex' : 'none' }}>
          <LibraryScreen />
        </View>
      ) : null}
      {visited.spaces ? (
        <View style={{ flex: 1, display: active === 'spaces' ? 'flex' : 'none' }}>
          <SpacesScreen />
        </View>
      ) : null}

      <BottomNav
        items={TABS}
        activeKey={active}
        onSelect={select}
        onCapture={() => router.push('/capture')}
      />
    </View>
  );
}
