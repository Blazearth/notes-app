import { Redirect, useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { View } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { useSession } from '@/auth/SessionProvider';
import { BottomNav, type NavItem } from '@/components/BottomNav';
import { morphProgress } from '@/motion/morph';
import { HomeScreen } from '@/screens/HomeScreen';
import { LibraryScreen } from '@/screens/LibraryScreen';
import { SpacesScreen } from '@/screens/SpacesScreen';
import { Radius } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

const TABS: NavItem[] = [
  { key: 'home', label: 'Home' },
  { key: 'library', label: 'Library' },
  { key: 'spaces', label: 'Spaces' },
];

/** Cross-fade duration. Short — this is a tab switch, not a page load. */
const FADE_MS = 170;

/**
 * How far the shell recedes while Settings expands over it.
 *
 * Small on purpose. The blur carries the separation; this only has to say that
 * the two surfaces are on different planes and that one of them is *behind*.
 * Any further and the shell reads as a shrinking screenshot rather than as the
 * app still being there, one layer down.
 */
const SHELL_RECEDE_SCALE = 0.94;

/**
 * One tab's content, stacked with its siblings and faded in when selected.
 *
 * Toggling `display` between `flex` and `none` is the cheap version and it
 * cuts hard: the outgoing screen vanishes a frame before the incoming one
 * exists, so the nav pill slides while the content teleports. Stacking the
 * panes and cross-fading keeps the two motions telling the same story.
 *
 * The panes stay mounted, so scroll position survives a switch — which is the
 * reason the shell keeps them alive in the first place.
 */
function TabPane({ active, children }: { active: boolean; children: React.ReactNode }) {
  const reduced = useReducedMotion();
  const progress = useSharedValue(active ? 1 : 0);

  useEffect(() => {
    const target = active ? 1 : 0;
    progress.value = reduced ? target : withTiming(target, { duration: FADE_MS });
  }, [active, reduced, progress]);

  const style = useAnimatedStyle(() => ({
    opacity: progress.value,
    // A hidden pane must not sit on top of the visible one swallowing touches.
    // Reanimated can drive `zIndex` off the same value, so visibility and hit
    // testing can never disagree the way two separate state updates can.
    zIndex: progress.value > 0.5 ? 1 : 0,
  }));

  return (
    <Animated.View
      // Belt and braces alongside `zIndex`: this is what actually stops a
      // faded-out pane from taking a tap mid-transition.
      pointerEvents={active ? 'auto' : 'none'}
      // Keep the inactive screens out of the accessibility tree, or a screen
      // reader walks three copies of the app.
      accessibilityElementsHidden={!active}
      importantForAccessibility={active ? 'auto' : 'no-hide-descendants'}
      style={[{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }, style]}
    >
      {children}
    </Animated.View>
  );
}

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
  const { session } = useSession();
  const router = useRouter();
  const reduced = useReducedMotion();
  const [active, setActive] = useState('home');
  const [visited, setVisited] = useState<Record<string, boolean>>({ home: true });

  /**
   * The shell's half of the Settings morph.
   *
   * Settings is a transparent modal, so this screen stays mounted and visible
   * underneath it — and a surface expanding over a shell that sits perfectly
   * still reads as a page covering another page. Receding on the *same* shared
   * value is what turns two screens into one interface with a front and a back.
   *
   * It is read from a module-scope shared value rather than passed down because
   * the value's owner is on the other side of a navigation boundary; see
   * `src/motion/morph.ts`.
   */
  const shellStyle = useAnimatedStyle(() => {
    // Reduced Motion snaps the morph to its end state, and a snap is exactly the
    // jolt the setting exists to avoid. The shell simply stays put.
    if (reduced) return {};
    const progress = interpolate(morphProgress.value, [0, 1], [0, 1], Extrapolation.CLAMP);
    return {
      transform: [{ scale: interpolate(progress, [0, 1], [1, SHELL_RECEDE_SCALE]) }],
      borderRadius: interpolate(progress, [0, 1], [0, Radius.xl]),
    };
  });

  if (!session) return <Redirect href="/sign-in" />;

  const select = (key: string) => {
    setVisited((prev) => (prev[key] ? prev : { ...prev, [key]: true }));
    setActive(key);
  };

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      <Animated.View style={[{ flex: 1, overflow: 'hidden' }, shellStyle]}>
        <View style={{ flex: 1 }}>
          {visited.home ? (
            <TabPane active={active === 'home'}>
              <HomeScreen />
            </TabPane>
          ) : null}
          {visited.library ? (
            <TabPane active={active === 'library'}>
              <LibraryScreen />
            </TabPane>
          ) : null}
          {visited.spaces ? (
            <TabPane active={active === 'spaces'}>
              <SpacesScreen />
            </TabPane>
          ) : null}
        </View>

        <BottomNav
          items={TABS}
          activeKey={active}
          onSelect={select}
          onCapture={() => router.push('/capture')}
        />
      </Animated.View>
    </View>
  );
}
