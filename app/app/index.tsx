import { Redirect, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, Modal, Platform, Pressable, StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  Extrapolation,
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { useSession } from '@/auth/SessionProvider';
import { AppText } from '@/components/AppText';
import { BottomNav, type NavItem } from '@/components/BottomNav';
import { Touchable } from '@/components/Touchable';
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
/** Keys in order, used by the swipe gesture. */
const TAB_KEYS = TABS.map((t) => t.key);

/** Cross-fade duration. Short — this is a tab switch, not a page load. */
const FADE_MS = 170;

/**
 * Themed exit-confirmation dialog.
 *
 * `Alert.alert` is a native OS dialog and cannot be styled. This component
 * reproduces the same two-button pattern using the app's own palette, radius,
 * and elevation tokens, so it feels like part of the product rather than a
 * system interruption.
 */
function ExitDialog({ visible, onStay, onExit }: { visible: boolean; onStay: () => void; onExit: () => void }) {
  const { palette, spacing, radius, elevation } = useTheme();
  return (
    <Modal transparent animationType="fade" visible={visible} onRequestClose={onStay} statusBarTranslucent>
      {/* Scrim — tap outside = stay */}
      <Pressable
        style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', alignItems: 'center', padding: spacing.xl }}
        onPress={onStay}
      >
        {/* Dialog card — absorb taps so pressing inside doesn't dismiss */}
        <Pressable
          style={[
            {
              width: '100%',
              backgroundColor: palette.surface,
              borderRadius: radius.xl,
              padding: spacing.xl,
              gap: spacing.lg,
            },
            elevation.sheet,
          ]}
        >
          <View style={{ gap: spacing.xs }}>
            <AppText variant="heading" style={{ fontSize: 18 }}>
              Exit Weavr?
            </AppText>
            <AppText tone="muted" style={{ fontSize: 14 }}>
              Are you sure you want to close the app?
            </AppText>
          </View>

          <View style={{ flexDirection: 'row', gap: spacing.md }}>
            {/* Stay — secondary outline button */}
            <Touchable
              onPress={onStay}
              weight="control"
              style={{
                flex: 1,
                paddingVertical: spacing.md,
                borderRadius: radius.pill,
                borderWidth: 1.5,
                borderColor: palette.border,
                alignItems: 'center',
              }}
            >
              <AppText variant="navLabel" style={{ color: palette.text }}>
                Stay
              </AppText>
            </Touchable>

            {/* Exit — accent filled button */}
            <Touchable
              onPress={onExit}
              weight="control"
              style={{
                flex: 1,
                paddingVertical: spacing.md,
                borderRadius: radius.pill,
                backgroundColor: palette.danger ?? palette.accent,
                alignItems: 'center',
              }}
            >
              <AppText variant="navLabel" style={{ color: '#ffffff' }}>
                Exit
              </AppText>
            </Touchable>
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}

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
  const [exitDialogVisible, setExitDialogVisible] = useState(false);

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

  /**
   * The nav layer's half of the same recede.
   *
   * The nav used to be a child of the shell above and so was carried by that
   * one transform. It is a sibling now (see the render below), which means it
   * needs its own — but it must be the *same* scale, applied to a layer that
   * fills the same rectangle. A transform scales about the view's own centre,
   * so a full-screen layer and the full-screen shell share an origin and the
   * two moves stay indistinguishable from the single one they replaced.
   *
   * No `borderRadius` here: it exists on the shell to round the receding page,
   * and this layer paints nothing of its own to round.
   */
  const navLayerStyle = useAnimatedStyle(() => {
    if (reduced) return {};
    const progress = interpolate(morphProgress.value, [0, 1], [0, 1], Extrapolation.CLAMP);
    return {
      transform: [{ scale: interpolate(progress, [0, 1], [1, SHELL_RECEDE_SCALE]) }],
    };
  });

  if (!session) return <Redirect href="/sign-in" />;

  const select = (key: string) => {
    setVisited((prev) => (prev[key] ? prev : { ...prev, [key]: true }));
    setActive(key);
  };

  /**
   * Stable callbacks for the swipe gesture worklet to call via runOnJS.
   *
   * Both read from `activeRef` rather than closing over `active`, so they
   * never go stale and the gesture never needs to be re-created.
   */
  const goToNextTab = useCallback(() => {
    const idx = TAB_KEYS.indexOf(activeRef.current);
    if (idx < TAB_KEYS.length - 1) {
      const key = TAB_KEYS[idx + 1];
      setVisited((prev) => (prev[key] ? prev : { ...prev, [key]: true }));
      setActive(key);
    }
  // activeRef is a stable ref — no dependency needed.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const goToPrevTab = useCallback(() => {
    const idx = TAB_KEYS.indexOf(activeRef.current);
    if (idx > 0) {
      const key = TAB_KEYS[idx - 1];
      setVisited((prev) => (prev[key] ? prev : { ...prev, [key]: true }));
      setActive(key);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Horizontal swipe gesture for tab switching.
   *
   * `activeOffsetX` — the gesture only activates after 15 px of horizontal
   * movement, giving vertical scrollers inside each tab a head start.
   *
   * `failOffsetY` — if the user moves 20 px vertically first the gesture
   * fails entirely, so lists and scroll views stay unaffected.
   *
   * The threshold inside `onEnd` (50 px OR 400 px/s) is deliberately generous:
   * too tight and accidental swipes switch tabs; too loose and intentional
   * swipes do nothing.
   */
  const swipeGesture = useMemo(
    () =>
      Gesture.Pan()
        .activeOffsetX([-15, 15])
        .failOffsetY([-20, 20])
        .onEnd((e) => {
          'worklet';
          if (e.translationX < -50 || e.velocityX < -400) {
            runOnJS(goToNextTab)();
          } else if (e.translationX > 50 || e.velocityX > 400) {
            runOnJS(goToPrevTab)();
          }
        }),
    [goToNextTab, goToPrevTab],
  );

  /**
   * Android back-button handling — edge cases covered:
   *
   * 1. A screen is pushed on the stack (Space detail, Settings, Capture, Search,
   *    Save detail, etc.): `router.canGoBack()` is true → return `false` so
   *    React Navigation pops it. Our tab logic never runs.
   *
   * 2. At the root of the tab shell on Library or Spaces: no stack to pop →
   *    jump back to Home tab.
   *
   * 3. At the root on Home: show the themed exit dialog.
   *
   * `activeRef` keeps the closure stable so the handler is only registered
   * once and never re-registered when the active tab changes.
   */
  const activeRef = useRef(active);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  useEffect(() => {
    if (Platform.OS !== 'android') return;

    const handler = BackHandler.addEventListener('hardwareBackPress', () => {
      // Case 1: React Navigation has a screen to pop — let it.
      if (router.canGoBack()) return false;

      // Case 2: Not on Home root — navigate back to Home tab.
      if (activeRef.current !== 'home') {
        select('home');
        return true;
      }

      // Case 3: On Home root — show themed exit dialog.
      setExitDialogVisible(true);
      return true;
    });

    return () => handler.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      <Animated.View style={[{ flex: 1, overflow: 'hidden' }, shellStyle]}>
        <GestureDetector gesture={swipeGesture}>
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
        </GestureDetector>
      </Animated.View>

      {/*
        The nav is a sibling of the shell with an explicit `zIndex`, and both
        halves of that are load-bearing.

        On the first device run it did not paint at all — neither the pill nor
        the capture button — while the screen behind them rendered fine. The
        cause is `TabPane`'s `zIndex: 1` above: painting order puts *any*
        positive z-index above an element that has none, whatever the source
        order, and the panes' container is a plain view that does not create a
        stacking context to keep that number to itself. So the active pane, an
        opaque full-bleed surface, was painted over a nav that had no z-index
        to answer with. Being later in the tree bought it nothing.

        Hoisting the nav into its own layer is what makes the two comparable at
        all — the shell's transform does establish a stacking context, so the
        pane's z-index is now contained by it. But that containment is a
        property of how transforms behave on web, so it is not something to
        rely on: `zIndex: 2` states the intent directly and holds wherever the
        containment does not. It has to stay above `TabPane`'s 1.

        `absoluteFill` reproduces the rectangle the nav used to inherit as a
        child, `box-none` keeps taps falling through to the feed everywhere the
        nav itself is not, and `navLayerStyle` carries the same recede so the
        Settings morph looks unchanged.
      */}
      <Animated.View
        pointerEvents="box-none"
        style={[StyleSheet.absoluteFill, { zIndex: 2 }, navLayerStyle]}
      >
        <BottomNav
          items={TABS}
          activeKey={active}
          onSelect={select}
          onCapture={() => router.push('/capture')}
        />
      </Animated.View>

      <ExitDialog
        visible={exitDialogVisible}
        onStay={() => setExitDialogVisible(false)}
        onExit={() => BackHandler.exitApp()}
      />
    </View>
  );
}
