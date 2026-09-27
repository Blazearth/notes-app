import { Redirect, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BackHandler, Dimensions, Modal, Platform, Pressable, StyleSheet, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  Extrapolation,
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';

import { track } from '@/analytics/client';
import { AnalyticsEvent, type ScreenName } from '@/analytics/events';
import { useSession } from '@/auth/SessionProvider';
import { AppText } from '@/components/AppText';
import { BottomNav, type NavItem } from '@/components/BottomNav';
import { Touchable } from '@/components/Touchable';
import { morphProgress } from '@/motion/morph';
import { activeTabFraction } from '@/motion/tabs';
import { Spring } from '@/theme/motion';
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
/** Tab keys in display order — index equals horizontal pane position. */
const TAB_KEYS = TABS.map((t) => t.key);

/**
 * Screen pixel width used to position pane offsets.
 * Stable for the life of the app on phone form factors.
 */
const SCREEN_WIDTH = Dimensions.get('window').width;

/** How far the shell recedes while Settings expands over it. */
const SHELL_RECEDE_SCALE = 0.94;

// ---------------------------------------------------------------------------
// Exit dialog
// ---------------------------------------------------------------------------

function ExitDialog({
  visible,
  onStay,
  onExit,
}: {
  visible: boolean;
  onStay: () => void;
  onExit: () => void;
}) {
  const { palette, spacing, radius, elevation } = useTheme();
  return (
    <Modal transparent animationType="fade" visible={visible} onRequestClose={onStay} statusBarTranslucent>
      <Pressable
        style={{
          flex: 1,
          backgroundColor: 'rgba(0,0,0,0.45)',
          justifyContent: 'center',
          alignItems: 'center',
          padding: spacing.xl,
        }}
        onPress={onStay}
      >
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

// ---------------------------------------------------------------------------
// Tab shell
// ---------------------------------------------------------------------------

/**
 * The root tab shell — Home / Library / Spaces.
 *
 * The three screens are laid out side-by-side in a single flexDirection:'row'
 * container that is 3× the screen width. `panX` (a Reanimated shared value)
 * translates this container left and right entirely on the UI thread, so panes
 * follow the finger with zero JS-thread involvement during drag.
 *
 * On release the container springs to the nearest tab. `runOnJS` then updates
 * React state so the nav pill stays in sync.
 */
export default function TabShell() {
  const { palette } = useTheme();
  const { session } = useSession();
  const router = useRouter();
  const reduced = useReducedMotion();

  const [active, setActive] = useState('home');
  const [exitDialogVisible, setExitDialogVisible] = useState(false);

  // ── Shared values ──────────────────────────────────────────────────────────

  /**
   * Horizontal position of the pane container.
   *   0              → Home    (index 0)
   *  -SCREEN_WIDTH   → Library (index 1)
   *  -2*SCREEN_WIDTH → Spaces  (index 2)
   */
  const panX = useSharedValue(0);

  /**
   * Mirrors the active tab index for worklet access (shared values cross
   * the JS/UI bridge; plain React state does not).
   */
  const activeIndexSV = useSharedValue(0);

  // ── Animated styles ────────────────────────────────────────────────────────

  const containerStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: panX.value }],
  }));

  const shellStyle = useAnimatedStyle(() => {
    if (reduced) return {};
    const progress = interpolate(morphProgress.value, [0, 1], [0, 1], Extrapolation.CLAMP);
    return {
      transform: [{ scale: interpolate(progress, [0, 1], [1, SHELL_RECEDE_SCALE]) }],
      borderRadius: interpolate(progress, [0, 1], [0, Radius.xl]),
    };
  });

  const navLayerStyle = useAnimatedStyle(() => {
    if (reduced) return {};
    const progress = interpolate(morphProgress.value, [0, 1], [0, 1], Extrapolation.CLAMP);
    return {
      transform: [{ scale: interpolate(progress, [0, 1], [1, SHELL_RECEDE_SCALE]) }],
    };
  });

  // ── Stable ref ─────────────────────────────────────────────────────────────

  const activeRef = useRef(active);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  // `screen_viewed`, scoped to Home/Library/Spaces per §G — the tab shell is
  // its own navigation model (a swipeable pane, not a route), so nothing at
  // the router level would ever see these transitions.
  useEffect(() => {
    track(AnalyticsEvent.ScreenViewed, { screen_name: 'home' });
  }, []);

  // ── Callbacks ──────────────────────────────────────────────────────────────

  /**
   * Primary tab-select: used by the nav pill, back button and any programmatic
   * navigation. Springs panX and syncs React state.
   */
  const selectTab = useCallback(
    (key: string) => {
      const idx = TAB_KEYS.indexOf(key);
      if (idx === -1) return;
      if (key !== activeRef.current) {
        track(AnalyticsEvent.ScreenViewed, { screen_name: key as ScreenName });
      }
      setActive(key);
      activeRef.current = key;
      activeIndexSV.value = idx;
      panX.value = withSpring(-idx * SCREEN_WIDTH, Spring.travel);
      activeTabFraction.value = withSpring(idx, Spring.travel);
    },
    // panX / activeIndexSV are stable Reanimated shared values, not React deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  /**
   * Called from the swipe worklet via runOnJS. Only updates React state;
   * panX and activeIndexSV are already set on the UI thread.
   */
  const setActiveFromIndex = useCallback((idx: number) => {
    const key = TAB_KEYS[idx];
    if (key !== activeRef.current) {
      track(AnalyticsEvent.ScreenViewed, { screen_name: key as ScreenName });
    }
    setActive(key);
    activeRef.current = key;
  }, []);

  // ── Swipe gesture ──────────────────────────────────────────────────────────

  /**
   * activeOffsetX  — activates after 15 px horizontal movement so vertical
   *                  scrollers inside panes get a head start.
   * failOffsetY    — fails entirely on 20 px vertical movement, leaving lists
   *                  and scroll views fully unaffected.
   * onUpdate       — runs on UI thread, moves panes in real time (no JS round-trip).
   * onEnd          — springs to nearest tab, biased by flick velocity.
   */
  const swipeGesture = useMemo(
    () =>
      Gesture.Pan()
        .activeOffsetX([-15, 15])
        .failOffsetY([-20, 20])
        .onUpdate((e) => {
          'worklet';
          const baseX = -activeIndexSV.value * SCREEN_WIDTH;
          const clamped = Math.min(
            0,
            Math.max(-(TAB_KEYS.length - 1) * SCREEN_WIDTH, baseX + e.translationX),
          );
          panX.value = clamped;
          // Keep the nav pill in sync on the UI thread — no JS round-trip needed.
          activeTabFraction.value = -clamped / SCREEN_WIDTH;
        })
        .onEnd((e) => {
          'worklet';
          const rawIndex = -panX.value / SCREEN_WIDTH;
          let target = Math.min(TAB_KEYS.length - 1, Math.max(0, Math.round(rawIndex)));
          if (e.velocityX < -400 && target < TAB_KEYS.length - 1) target += 1;
          if (e.velocityX > 400 && target > 0) target -= 1;
          panX.value = withSpring(-target * SCREEN_WIDTH, { damping: 24, stiffness: 240, mass: 0.9 });
          activeTabFraction.value = withSpring(target, { damping: 24, stiffness: 240, mass: 0.9 });
          activeIndexSV.value = target;
          runOnJS(setActiveFromIndex)(target);
        }),
    [setActiveFromIndex],
  );

  // ── Android back button ────────────────────────────────────────────────────

  /**
   * Case 1: pushed screen → React Navigation pops it (return false).
   * Case 2: not on Home root → go to the previous tab in order
   *         (Spaces → Library → Home) with the same spring as a swipe.
   * Case 3: on Home root → show themed exit dialog.
   */
  useEffect(() => {
    if (Platform.OS !== 'android') return;

    const handler = BackHandler.addEventListener('hardwareBackPress', () => {
      if (router.canGoBack()) return false;

      const currentIndex = TAB_KEYS.indexOf(activeRef.current);
      if (currentIndex > 0) {
        selectTab(TAB_KEYS[currentIndex - 1]);
        return true;
      }

      setExitDialogVisible(true);
      return true;
    });

    return () => handler.remove();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // All hooks above — Rules of Hooks satisfied before the early return.
  if (!session) return <Redirect href="/sign-in" />;

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      <Animated.View style={[{ flex: 1, overflow: 'hidden' }, shellStyle]}>
        <GestureDetector gesture={swipeGesture}>
          {/*
            Three panes side-by-side in a 3× wide container. overflow:'hidden'
            on the parent clips off-screen panes. pointerEvents="none" blocks
            touches to inactive panes even during mid-swipe transitions.
          */}
          <Animated.View
            style={[
              {
                flexDirection: 'row',
                width: SCREEN_WIDTH * TAB_KEYS.length,
                height: '100%',
              },
              containerStyle,
            ]}
          >
            <View
              style={{ width: SCREEN_WIDTH, height: '100%' }}
              pointerEvents={active === 'home' ? 'auto' : 'none'}
            >
              <HomeScreen />
            </View>
            <View
              style={{ width: SCREEN_WIDTH, height: '100%' }}
              pointerEvents={active === 'library' ? 'auto' : 'none'}
            >
              <LibraryScreen />
            </View>
            <View
              style={{ width: SCREEN_WIDTH, height: '100%' }}
              pointerEvents={active === 'spaces' ? 'auto' : 'none'}
            >
              <SpacesScreen />
            </View>
          </Animated.View>
        </GestureDetector>
      </Animated.View>

      {/*
        Nav layer is a sibling of the shell so it is never clipped by
        overflow:'hidden', and sits above the panes via zIndex:2.
      */}
      <Animated.View
        pointerEvents="box-none"
        style={[StyleSheet.absoluteFill, { zIndex: 2 }, navLayerStyle]}
      >
        <BottomNav
          items={TABS}
          activeKey={active}
          onSelect={selectTab}
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
