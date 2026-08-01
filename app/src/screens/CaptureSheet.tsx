import * as Clipboard from 'expo-clipboard';
import { BlurView } from 'expo-blur';
import { useRouter } from 'expo-router';
import React, { useEffect, useState } from 'react';
import { ActivityIndicator, Platform, Pressable, View } from 'react-native';
import Animated, {
  interpolate,
  runOnJS,
  useAnimatedProps,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ApiError, createSave } from '@/api/client';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Touchable } from '@/components/Touchable';
import { CAPTURE_OPTIONS, CAPTURE_SUBTITLE, type CaptureOption } from '@/data/sampleContent';
import { useHaptic } from '@/motion/haptics';
import { useSaves } from '@/saves/SavesProvider';
import { Spring, staggerDelay } from '@/theme/motion';
import { useTheme } from '@/theme/ThemeProvider';

const COLUMNS = 4;

/** Only `link` posts today; the rest need capture surfaces that do not exist. */
const IMPLEMENTED: ReadonlySet<string> = new Set(['link']);

const AnimatedBlurView = Animated.createAnimatedComponent(BlurView);

/** Full backdrop blur at rest. Animated from 0 so the blur grows in, not just fades in. */
const BACKDROP_BLUR_INTENSITY = 20;
/** A "subtle dark scrim over the blur" per the design brief — kept well under the 55% `Alpha.scrim` used for solid (non-blurred) dims elsewhere. */
const BACKDROP_SCRIM_OPACITY = 0.16;
/** Off-screen starting offset for the panel — larger than any real sheet height so it always begins fully hidden below the fold. */
const PANEL_ENTER_OFFSET = 420;
/** How far a tile rises into place. Small — this is a garnish on a sheet that is already moving. */
const TILE_ENTER_OFFSET = 12;
const TILE_ENTER_MS = 260;

function chunk<T>(items: T[], size: number): T[][] {
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += size) rows.push(items.slice(i, i + size));
  return rows;
}

function looksLikeUrl(value: string): boolean {
  try {
    const url = new URL(value.trim());
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

function OptionTile({
  option,
  busy,
  index,
  onPress,
}: {
  option: CaptureOption;
  busy: boolean;
  /** Position in the flattened grid, so the tiles arrive in reading order. */
  index: number;
  onPress: () => void;
}) {
  const { palette, radius, spacing, layout, alpha } = useTheme();
  const reduced = useReducedMotion();
  const enabled = IMPLEMENTED.has(option.id);
  const tint =
    option.tint === 'accent' ? palette.accent : option.tint === 'warm' ? palette.warning : palette.textMuted;

  /**
   * A plain animated style rather than `entering={FadeInDown…}`.
   *
   * Not the fix — the collapsed tiles were a Yoga sizing rule, see the `style`
   * comment below. This started as a wrong guess at that bug and is kept only
   * because it is strictly less entangled with layout: an entering animation is
   * driven by the layout-animation manager and can touch the view's box, while
   * an animated style only paints. `Reveal` still uses `entering` and is fine,
   * so there is nothing wrong with the other approach either.
   */
  const progress = useSharedValue(reduced ? 1 : 0);

  useEffect(() => {
    if (reduced) {
      progress.value = 1;
      return;
    }
    progress.value = withDelay(staggerDelay(index), withTiming(1, { duration: TILE_ENTER_MS }));
  }, [index, reduced, progress]);

  const enterStyle = useAnimatedStyle(() => ({
    opacity: progress.value,
    transform: [{ translateY: (1 - progress.value) * TILE_ENTER_OFFSET }],
  }));

  return (
    <Animated.View style={[{ flex: 1 }, enterStyle]}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={option.label}
        accessibilityState={{ disabled: !enabled || busy }}
        disabled={!enabled || busy}
        onPress={onPress}
        weight="tile"
        // Silent on the tiles that do nothing yet: a buzz is a promise that
        // something happened, and eleven of these twelve do not act.
        haptic={enabled ? 'medium' : null}
        baseOpacity={enabled ? 1 : alpha.disabled}
        /*
         * No `flex: 1` here, and that is the whole bug.
         *
         * `flex: 1` means `flexBasis: 0`, and this Pressable's parent is a
         * column whose height is auto. Yoga resolves a zero-basis child in a
         * container with an indefinite main axis to **zero height** — so the
         * tile measured 0 tall, both rows were laid out at the same y, and the
         * footer rode up under the subtitle. The icons still painted, because
         * React Native does not clip overflow by default, which is what made it
         * read as an animation stuck mid-flight rather than as a collapsed box.
         *
         * CSS does not do this: an auto-height flex container sizes to
         * max-content, so the identical tree measures correctly in a browser.
         * That divergence is why the headless-Chrome check passed this screen —
         * it is not a harness that can see this class of bug, and any future
         * "correct on web, wrong on device" layout should be read as a Yoga
         * sizing rule before anything else.
         *
         * The width still comes from the wrapping `Animated.View`'s `flex: 1`,
         * and a column parent stretches its children horizontally by default,
         * so dropping this changes nothing about how wide the tile is.
         */
        style={{ alignItems: 'center', gap: spacing.sm }}
      >
        <View
          style={{
            width: layout.minTouchTarget + 12,
            height: layout.minTouchTarget + 12,
            borderRadius: radius.lg,
            backgroundColor: palette.surfaceVariant,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {busy ? (
            <ActivityIndicator color={palette.accent} />
          ) : (
            <Glyph name={option.glyph} size={20} weight={2} color={tint} />
          )}
        </View>
        <AppText variant="caption" style={{ fontSize: 11, textAlign: 'center' }}>
          {option.label}
        </AppText>
      </Touchable>
    </Animated.View>
  );
}

/**
 * Universal Capture — the FAB sheet.
 *
 * In-app capture is the *secondary* path: the primary one is the OS share sheet,
 * which never opens the app. This exists for content the user is holding rather
 * than viewing (a link on the clipboard, a photo, a typed note).
 */
export function CaptureSheet() {
  const { palette, radius, spacing, layout, elevation, alpha, blurEffects } = useTheme();
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const { prepend } = useSaves();
  const haptic = useHaptic();

  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reducedMotion = useReducedMotion();
  // Drives the backdrop blur/scrim and the panel's rise together, on the same
  // spring, so the expansion reads as one motion instead of two coincidentally
  // timed ones. Reversing it and waiting for it to settle before navigating
  // back is what makes dismissal a true mirror of the entrance rather than a
  // cut to the Stack's own fade.
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = reducedMotion ? 1 : withSpring(1, Spring.enter);
    // Mount-only: this is the sheet's entrance, it never re-runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const dismiss = () => {
    if (reducedMotion) {
      router.back();
      return;
    }
    progress.value = withSpring(0, Spring.enter, (finished) => {
      if (finished) runOnJS(router.back)();
    });
  };

  const blurProps = useAnimatedProps(() => ({
    intensity: progress.value * BACKDROP_BLUR_INTENSITY,
  }));
  const scrimStyle = useAnimatedStyle(() => ({
    opacity: progress.value * BACKDROP_SCRIM_OPACITY,
  }));
  const fallbackDimStyle = useAnimatedStyle(() => ({
    opacity: progress.value * (alpha.scrim + 0.3),
  }));
  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateY: interpolate(progress.value, [0, 1], [PANEL_ENTER_OFFSET, 0]) }],
  }));

  const pasteLink = async () => {
    setBusyId('link');
    setError(null);
    try {
      const clipboard = (await Clipboard.getStringAsync()).trim();
      if (!clipboard) {
        haptic('error');
        setError('Clipboard is empty. Copy a link first.');
        return;
      }
      if (!looksLikeUrl(clipboard)) {
        haptic('error');
        setError('That does not look like a link. Copy an http(s) URL and try again.');
        return;
      }

      const save = await createSave({ sourceType: 'url', sourceUrl: clipboard });
      // The sheet dismisses on success, so the confirmation has to be tactile —
      // there is no surface left to show a check on.
      haptic('success');
      prepend(save);
      router.back();
    } catch (e) {
      // Surface the real reason: an unreachable API and a rejected token look
      // identical to a user otherwise, and both are common in development.
      haptic('error');
      setError(e instanceof ApiError ? e.message : 'Could not save that link');
    } finally {
      setBusyId(null);
    }
  };

  const rows = chunk(CAPTURE_OPTIONS, COLUMNS);

  return (
    <View style={{ flex: 1, justifyContent: 'flex-end' }}>
      <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}>
        <Pressable accessibilityRole="button" accessibilityLabel="Dismiss" onPress={dismiss} style={{ flex: 1 }}>
          {blurEffects ? (
            <View style={{ flex: 1 }}>
              <AnimatedBlurView
                animatedProps={blurProps}
                tint={palette.isDark ? 'dark' : 'light'}
                experimentalBlurMethod={Platform.OS === 'android' ? 'dimezisBlurView' : undefined}
                style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
              />
              {/* A flat dark tint on top of the blur, kept subtle so the screen
                  beneath stays legible — the blur carries the separation, the
                  scrim only adds contrast. */}
              <Animated.View
                style={[
                  { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: '#000000' },
                  scrimStyle,
                ]}
              />
            </View>
          ) : (
            <Animated.View
              style={[{ flex: 1, backgroundColor: palette.scrim }, fallbackDimStyle]}
            />
          )}
        </Pressable>
      </View>

      {/*
        The panel rises from the bottom edge on the same `progress` spring that
        drives the backdrop, so expansion and dismissal are one motion rather
        than two animations that merely happen to overlap.
      */}
      <Animated.View
        style={[
          {
            backgroundColor: palette.surface,
            borderTopLeftRadius: radius.xl,
            borderTopRightRadius: radius.xl,
            paddingTop: spacing.md,
            paddingHorizontal: layout.screenGutter,
            paddingBottom: spacing.xxl + insets.bottom,
            ...elevation.sheet,
          },
          panelStyle,
        ]}
      >
        <View
          style={{
            width: 36,
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.border,
            alignSelf: 'center',
            marginBottom: spacing.lg + 2,
          }}
        />
        <AppText variant="heading" style={{ marginBottom: spacing.xs }}>
          Add to Weavr
        </AppText>
        <AppText tone="muted" style={{ fontSize: 12.5, marginBottom: spacing.xl }}>
          {CAPTURE_SUBTITLE}
        </AppText>

        <View style={{ gap: spacing.md + 2 }}>
          {rows.map((row, rowIndex) => (
            <View key={rowIndex} style={{ flexDirection: 'row', gap: spacing.md + 2 }}>
              {row.map((option, colIndex) => (
                <OptionTile
                  key={option.id}
                  option={option}
                  busy={busyId === option.id}
                  index={rowIndex * COLUMNS + colIndex}
                  onPress={option.id === 'link' ? () => void pasteLink() : () => {}}
                />
              ))}
              {/* Keep the last row's columns aligned with the first. */}
              {Array.from({ length: COLUMNS - row.length }).map((_, i) => (
                <View key={`spacer-${i}`} style={{ flex: 1 }} />
              ))}
            </View>
          ))}
        </View>

        {error ? (
          <AppText variant="caption" style={{ color: palette.danger, marginTop: spacing.lg }}>
            {error}
          </AppText>
        ) : (
          <AppText variant="caption" tone="faint" style={{ marginTop: spacing.lg }}>
            Paste Link is wired to the API. The rest arrive with their capture surfaces.
          </AppText>
        )}
      </Animated.View>
    </View>
  );
}
