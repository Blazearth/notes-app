import { BlurView } from 'expo-blur';
import { useFocusEffect, useRouter } from 'expo-router';
import React, { createContext, useCallback, useContext, useEffect, useRef } from 'react';
import { BackHandler, Platform, useWindowDimensions, View } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  interpolateColor,
  runOnJS,
  useAnimatedProps,
  useAnimatedStyle,
  useReducedMotion,
  withSpring,
  withTiming,
} from 'react-native-reanimated';

import { Glyph, type GlyphName } from '@/components/Glyph';
import { morphOrigin, morphProgress } from '@/motion/morph';
import { Spring } from '@/theme/motion';
import { Duration } from '@/theme/tokens';
import { useTheme } from '@/theme/ThemeProvider';

const AnimatedBlurView = Animated.createAnimatedComponent(BlurView);

/** Backdrop blur at full expansion. Matches the capture sheet's backdrop. */
const BACKDROP_BLUR_INTENSITY = 24;
/** Tint over the blur — enough to separate the two planes, not enough to hide one. */
const BACKDROP_SCRIM_OPACITY = 0.18;

/**
 * When the content crossfades, as a fraction of the geometric morph.
 *
 * Material's container transform in one line: the *container* moves first and
 * the contents arrive into it, rather than a full page being scaled up. Starting
 * at 0 would scale legible text from thumbnail size (which looks broken); ending
 * at 1 would let the content still be settling after the surface has stopped.
 */
const CONTENT_REVEAL = [0.18, 0.74] as const;

/** How long the source control stays legible inside the growing surface. */
const SOURCE_FADE = [0, 0.22] as const;

/**
 * Dismissal, exposed to whatever is rendered inside.
 *
 * `null` when a screen is rendered outside a morph — a deep link straight to
 * `/settings`, say — so callers can fall back to plain `router.back()` rather
 * than branching on a boolean.
 */
const MorphDismissContext = createContext<(() => void) | null>(null);

/** The collapse-back-into-the-button dismissal, or `null` if not presented as a morph. */
export function useMorphDismiss(): (() => void) | null {
  return useContext(MorphDismissContext);
}

export interface MorphPresentationProps {
  /**
   * The glyph on the control that opened this screen. Drawn inside the surface
   * at its starting size and faded out as it grows, so the first frames are
   * indistinguishable from the button still sitting there.
   */
  sourceGlyph: GlyphName;
  children: React.ReactNode;
}

/**
 * Presents a screen as a surface that grows out of the control that opened it,
 * and collapses back into it on dismissal.
 *
 * The route is a `transparentModal` with `animation: 'none'` — the stack does
 * no transition of its own, this component owns the whole thing. That is what
 * makes it reversible: a stack animation cannot be run backwards on demand, so
 * a push/pop pair always cuts at the moment of navigation, however carefully the
 * two halves are matched.
 *
 * Everything is driven off one shared value (`morphProgress`), so the surface's
 * geometry, its corner radius, its colour, the backdrop blur, the content fade
 * and the shell receding *behind* this screen are all the same motion sampled in
 * different places — they cannot drift, because there is nothing to drift from.
 *
 * Performance: the only per-frame layout is this one childless-frame container.
 * The content inside is a fixed-size subtree moved with `transform` and
 * `opacity`, which the compositor handles without re-laying anything out — so
 * the cost of the morph does not grow with the complexity of the screen.
 */
export function MorphPresentation({ sourceGlyph, children }: MorphPresentationProps) {
  const { palette, radius, elevation, alpha, icon, blurEffects } = useTheme();
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
  const router = useRouter();
  const reducedMotion = useReducedMotion();

  // Guards the collapse against a second tap, and against the hardware back
  // button landing on top of the button the user already pressed. Both would
  // otherwise pop twice — leaving the stack a screen short of where it should be.
  const dismissing = useRef(false);

  useEffect(() => {
    morphProgress.value = reducedMotion ? 1 : withSpring(1, Spring.morph);

    return () => {
      // Whatever removed this screen — our own dismissal, a deep link, a reload
      // during development — the shell underneath must not be left scaled down
      // and blurred with nothing on top of it. Cheap insurance: after a normal
      // dismissal the value is already 0 and this is a no-op.
      if (morphProgress.value !== 0) {
        morphProgress.value = withTiming(0, { duration: Duration.medium });
      }
    };
    // Mount-only: this is the screen's entrance and it never re-runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const releaseDismissGuard = useCallback(() => {
    dismissing.current = false;
  }, []);

  const dismiss = useCallback(() => {
    if (dismissing.current) return;
    dismissing.current = true;

    if (reducedMotion) {
      morphProgress.value = 0;
      router.back();
      return;
    }

    // Navigate only once the surface has actually arrived back at the button.
    // Popping first and animating after would unmount this screen mid-spring.
    morphProgress.value = withSpring(0, Spring.morph, (finished) => {
      'worklet';
      if (finished) runOnJS(router.back)();
      // Interrupted — something retargeted the spring. Leave the screen up and
      // let the user ask again rather than stranding a dead back button.
      else runOnJS(releaseDismissGuard)();
    });
  }, [reducedMotion, releaseDismissGuard, router]);

  // Android's back button must play the same collapse, not the stack's instant
  // pop. `gestureEnabled: false` on the route stops iOS's swipe from bypassing
  // it the same way.
  //
  // Bound to focus rather than to mount, because this screen stays mounted while
  // it pushes further (Settings → Appearance). `BackHandler` runs the most
  // recently registered handler first, so a mount-scoped listener here would win
  // over the navigator's while Appearance is on top — and back from Appearance
  // would collapse Settings instead, skipping a screen.
  useFocusEffect(
    useCallback(() => {
      // Web has no hardware back, and react-native-web's stub logs an error
      // rather than no-opping — so ask for it only where it exists.
      if (Platform.OS === 'web') return;
      const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
        dismiss();
        return true;
      });
      return () => subscription.remove();
    }, [dismiss]),
  );

  /**
   * The morphing surface itself: a window that starts as the button's rectangle
   * and opens to the full screen.
   *
   * `left`/`top`/`width`/`height` are interpolated *without* clamping, so the
   * spring's overshoot expresses as the surface pushing a little past the screen
   * edge and settling back — the one place the overshoot should be felt. The
   * radius and the colour are clamped, because a negative corner radius is not a
   * shape and an over-extrapolated colour is not a colour.
   */
  const surfaceStyle = useAnimatedStyle(() => {
    const progress = morphProgress.value;
    const origin = morphOrigin.value;
    // No measured source (a deep link, or a measurement that timed out): start
    // from the full-screen rectangle so the morph degrades to the content fade
    // below rather than growing out of the top-left corner.
    const anchored = origin.width > 0 && origin.height > 0;

    const settled = interpolate(progress, [0, 1], [0, 1], Extrapolation.CLAMP);

    return {
      left: interpolate(progress, [0, 1], [anchored ? origin.x : 0, 0]),
      top: interpolate(progress, [0, 1], [anchored ? origin.y : 0, 0]),
      width: interpolate(progress, [0, 1], [anchored ? origin.width : screenWidth, screenWidth]),
      height: interpolate(progress, [0, 1], [anchored ? origin.height : screenHeight, screenHeight]),
      borderRadius: interpolate(settled, [0, 1], [anchored ? origin.radius : radius.xl, radius.xl]),
      // Starts as the control's surface colour and becomes the page's, so the
      // first frames are the button and the last are the screen, with no step
      // between them. The content has faded in over its own background well
      // before this finishes, so the crossover is never visible as a colour change.
      backgroundColor: interpolateColor(
        settled,
        [0, 0.35],
        [palette.surface, palette.background],
      ),
    };
  });

  /**
   * The screen's real content, held at its final size and position throughout.
   *
   * Counter-translating by the surface's own offset is what keeps it still: the
   * container is what moves and grows, and the content behind it never shifts,
   * so the eye reads an aperture opening rather than a page flying in. A page
   * that flies in is the slide transition this replaces.
   */
  const contentStyle = useAnimatedStyle(() => {
    const progress = morphProgress.value;
    const origin = morphOrigin.value;
    const anchored = origin.width > 0 && origin.height > 0;

    const reveal = interpolate(
      progress,
      [CONTENT_REVEAL[0], CONTENT_REVEAL[1]],
      [0, 1],
      Extrapolation.CLAMP,
    );

    return {
      opacity: reveal,
      transform: [
        { translateX: -interpolate(progress, [0, 1], [anchored ? origin.x : 0, 0]) },
        { translateY: -interpolate(progress, [0, 1], [anchored ? origin.y : 0, 0]) },
        // A last percent of travel on the content itself, against the surface's
        // much larger one. Two speeds is what makes a flat crossfade feel like
        // depth; a single one reads as a dissolve.
        { scale: interpolate(reveal, [0, 1], [0.97, 1]) },
      ],
    };
  });

  /**
   * The source control, redrawn inside the surface at its own size.
   *
   * Without this the very first frame is a 36pt empty tile where a gear was, and
   * the eye catches the substitution. It leaves early — by the time the surface
   * is a fifth open there is nothing gear-shaped left to look at — and on the
   * way back it fades in to land exactly under the finger.
   */
  const sourceStyle = useAnimatedStyle(() => {
    const progress = morphProgress.value;
    const origin = morphOrigin.value;

    return {
      width: origin.width,
      height: origin.height,
      opacity: interpolate(progress, [SOURCE_FADE[0], SOURCE_FADE[1]], [1, 0], Extrapolation.CLAMP),
      transform: [
        {
          scale: interpolate(
            progress,
            [SOURCE_FADE[0], SOURCE_FADE[1]],
            [1, 1.3],
            Extrapolation.CLAMP,
          ),
        },
      ],
    };
  });

  const blurProps = useAnimatedProps(() => ({
    intensity: interpolate(
      morphProgress.value,
      [0, 1],
      [0, BACKDROP_BLUR_INTENSITY],
      Extrapolation.CLAMP,
    ),
  }));

  const scrimStyle = useAnimatedStyle(() => ({
    opacity: interpolate(
      morphProgress.value,
      [0, 1],
      [0, BACKDROP_SCRIM_OPACITY],
      Extrapolation.CLAMP,
    ),
  }));

  const fallbackDimStyle = useAnimatedStyle(() => ({
    opacity: interpolate(morphProgress.value, [0, 1], [0, alpha.scrim], Extrapolation.CLAMP),
  }));

  return (
    <View style={{ flex: 1 }}>
      {/* The backdrop is mounted for the whole life of the screen and only ever
          changes intensity, so there is no point at which it is added or removed
          — which is what would produce the flash the brief rules out. */}
      {blurEffects ? (
        <>
          <AnimatedBlurView
            animatedProps={blurProps}
            tint={palette.isDark ? 'dark' : 'light'}
            experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
            style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
          />
          <Animated.View
            style={[
              { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: '#000000' },
              scrimStyle,
            ]}
          />
        </>
      ) : (
        <Animated.View
          style={[
            { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: palette.scrim },
            fallbackDimStyle,
          ]}
        />
      )}

      <Animated.View
        style={[
          {
            position: 'absolute',
            // Clips the content to the aperture. Without it the full-size
            // content renders outside the surface from the first frame and
            // there is no morph at all, just a fade.
            overflow: 'hidden',
            // Carried at full strength from the first frame to the last: the
            // surface is lifted off the page for the entire journey, so it never
            // gains or loses a plane mid-flight.
            borderWidth: 1,
            borderColor: palette.border,
            ...elevation.nav,
          },
          surfaceStyle,
        ]}
      >
        <Animated.View
          style={[{ position: 'absolute', width: screenWidth, height: screenHeight }, contentStyle]}
        >
          <MorphDismissContext.Provider value={dismiss}>{children}</MorphDismissContext.Provider>
        </Animated.View>

        <Animated.View
          pointerEvents="none"
          style={[{ position: 'absolute', left: 0, top: 0, alignItems: 'center', justifyContent: 'center' }, sourceStyle]}
        >
          <Glyph name={sourceGlyph} size={icon.sm} />
        </Animated.View>
      </Animated.View>
    </View>
  );
}
