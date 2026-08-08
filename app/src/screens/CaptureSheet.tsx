import * as Clipboard from 'expo-clipboard';
import { BlurView } from 'expo-blur';
import * as ImagePicker from 'expo-image-picker';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Alert, KeyboardAvoidingView, Platform, Pressable, TextInput, View, useWindowDimensions } from 'react-native';
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
  type SharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Touchable } from '@/components/Touchable';
import { CAPTURE_OPTIONS, CAPTURE_SUBTITLE, type CaptureOption } from '@/data/sampleContent';
import { useHaptic } from '@/motion/haptics';
import { useSaves } from '@/saves/SavesProvider';
import { Spring, staggerDelay } from '@/theme/motion';
import { useTheme } from '@/theme/ThemeProvider';
import { supabase } from '@/auth/supabase';
import { SUPABASE_URL } from '@/api/config';

const COLUMNS = 4;

/** Only `link`, `note`, and `screenshot` post today; the rest need capture surfaces. */
const IMPLEMENTED: ReadonlySet<string> = new Set(['link', 'note', 'screenshot']);

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
/** Dismissal duration. Fixed, so navigation lands at a known moment — see `dismiss`. */
const DISMISS_MS = 160;
/** The tiles clear out before the surface starts to move. Short — this is a beat, not a stage. */
const TILE_EXIT_MS = 90;
/** How small the panel starts, i.e. roughly FAB-sized against the sheet's width. */
const PANEL_ENTER_SCALE = 0.82;
/** Tiles arrive slightly small as well as faint — a fade alone reads as a slideshow. */
const TILE_ENTER_SCALE = 0.86;

/** Module scope so its identity never changes. */
const noop = () => {};

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

const OptionTile = React.memo(function OptionTile({
  option,
  busy,
  index,
  onPress,
  tilesOut,
}: {
  option: CaptureOption;
  busy: boolean;
  /** Position in the flattened grid, so the tiles arrive in reading order. */
  index: number;
  onPress: () => void;
  /** The sheet's exit multiplier — 1 until dismissal starts. See `CaptureSheet`. */
  tilesOut: SharedValue<number>;
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

  // Fade and scale together, and both fold in the exit multiplier — so the
  // same style serves the staggered arrival and the ordered departure without
  // the component knowing which one is running.
  const enterStyle = useAnimatedStyle(() => {
    const shown = progress.value * tilesOut.value;
    return {
      opacity: shown,
      transform: [
        { translateY: (1 - progress.value) * TILE_ENTER_OFFSET },
        { scale: interpolate(shown, [0, 1], [TILE_ENTER_SCALE, 1]) },
      ],
    };
  });

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
});

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
  const { width } = useWindowDimensions();
  const router = useRouter();
  const { prepend } = useSaves();
  const haptic = useHaptic();

  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 'tiles' — the grid; 'note' — the inline text editor. */
  const [mode, setMode] = useState<'tiles' | 'note'>('tiles');
  const [noteTitle, setNoteTitle] = useState('');
  const [noteText, setNoteText] = useState('');

  const reducedMotion = useReducedMotion();
  // Drives the backdrop blur/scrim and the panel's rise together, on the same
  // spring, so the expansion reads as one motion instead of two coincidentally
  // timed ones. Reversing it and waiting for it to settle before navigating
  // back is what makes dismissal a true mirror of the entrance rather than a
  // cut to the Stack's own fade.
  const progress = useSharedValue(0);

  /**
   * The tiles' own multiplier, so open and close are not simple mirrors.
   *
   * Opening, the surface arrives first and its contents follow — the tiles are
   * staggered off `progress`. Closing has to run the other way round: contents
   * leave, *then* the surface. Driving both from one value cannot express that,
   * because one value has one ordering. This one only ever moves on the way
   * out, which is why it starts at 1 and is never animated up.
   */
  const tilesOut = useSharedValue(1);

  useEffect(() => {
    progress.value = reducedMotion ? 1 : withSpring(1, Spring.enter);
    // Mount-only: this is the sheet's entrance, it never re-runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Close, fast and deterministically.
   *
   * This used to run the entrance spring backwards and navigate from its
   * completion callback, which is where the lag came from: a spring settles
   * asymptotically, so it spends a long tail travelling a few pixels nobody can
   * see, and `router.back()` waited for all of it. The sheet looked gone well
   * before the screen changed.
   *
   * A timing curve has an end. 160 ms is under the threshold where a dismissal
   * reads as a wait, and because the duration is fixed, the navigation lands at
   * a known moment rather than whenever the physics decide.
   *
   * Guarded against re-entry: two taps on the scrim would otherwise fire
   * `router.back()` twice and pop the screen underneath as well.
   */
  const dismissing = useRef(false);
  const dismiss = useCallback(() => {
    if (dismissing.current) return;
    dismissing.current = true;

    if (reducedMotion) {
      router.back();
      return;
    }

    // Contents out first, surface after. The delay is what makes the exit a
    // considered reversal rather than the entrance played backwards — the tiles
    // are gone by the time the panel starts moving, so nothing is still fading
    // while the thing holding it slides away underneath.
    tilesOut.value = withTiming(0, { duration: TILE_EXIT_MS });
    progress.value = withDelay(
      TILE_EXIT_MS,
      withTiming(0, { duration: DISMISS_MS }, (finished) => {
        if (finished) runOnJS(router.back)();
      }),
    );
  }, [reducedMotion, router, progress, tilesOut]);

  const blurProps = useAnimatedProps(() => ({
    intensity: progress.value * BACKDROP_BLUR_INTENSITY,
  }));
  const scrimStyle = useAnimatedStyle(() => ({
    opacity: progress.value * BACKDROP_SCRIM_OPACITY,
  }));
  const fallbackDimStyle = useAnimatedStyle(() => ({
    opacity: progress.value * (alpha.scrim + 0.3),
  }));

  /**
   * The panel grows out of the FAB, and shrinks back into it.
   *
   * `transformOrigin` is what does the work: pinned to the FAB's centre on the
   * x axis and the panel's own bottom edge on the y, a plain scale reads as the
   * surface expanding from the button rather than as a card being zoomed. The
   * remaining `translateY` keeps a little of the upward travel a sheet is
   * expected to have.
   *
   * The FAB's position is computed from the layout tokens that place it rather
   * than measured. It is `fabSize` square, inset by `navInset` from the right —
   * so its centre is arithmetic, available on the first frame, and immune to
   * the stale-rectangle problem that a `measureInWindow` round trip has on the
   * very first open (see `motion/morph.ts`, where measurement *was* required
   * because the gear tile scrolls).
   */
  const fabCenterX = width - layout.navInset - layout.fabSize / 2;
  const panelStyle = useAnimatedStyle(() => ({
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [PANEL_ENTER_OFFSET, 0]) },
      { scale: interpolate(progress.value, [0, 1], [PANEL_ENTER_SCALE, 1]) },
    ],
    opacity: interpolate(progress.value, [0, 0.35, 1], [0, 1, 1]),
  }));

  /**
   * Validate on the sheet, then dismiss *before* the network call.
   *
   * The split matters. Clipboard read and URL validation are local and
   * sub-frame, and their failures are the user's to correct — an empty
   * clipboard has to say so on a sheet that is still open, or the message has
   * nowhere to land. Creating the save is a round trip, and waiting for it held
   * the sheet on screen for its whole duration, which is the lag being
   * reported: the tap looked ignored until the server answered.
   *
   * So the create is fired and deliberately not awaited. The sheet is already
   * closing while it runs, and the feed updates underneath when it lands.
   */
  const pasteLink = useCallback(async () => {
    setBusyId('link');
    setError(null);

    let clipboard: string;
    try {
      clipboard = (await Clipboard.getStringAsync()).trim();
    } catch {
      haptic('error');
      setError('Could not read the clipboard.');
      setBusyId(null);
      return;
    }

    if (!clipboard) {
      haptic('error');
      setError('Clipboard is empty. Copy a link first.');
      setBusyId(null);
      return;
    }
    if (!looksLikeUrl(clipboard)) {
      haptic('error');
      setError('That does not look like a link. Copy an http(s) URL and try again.');
      setBusyId(null);
      return;
    }

    // Committed from here. Confirm tactilely and get out of the way — there is
    // no surface left to show a check on.
    haptic('success');
    setBusyId(null);
    dismiss();

    void repo
      .createSave({ sourceType: 'url', sourceUrl: clipboard })
      .then(prepend)
      .catch((e: unknown) => {
        haptic('error');
        console.warn('[capture] save failed:', e instanceof ApiError ? e.message : e);
      });
  }, [dismiss, haptic, prepend]);

  // `pasteLink` has to be stable too, or this changes every render and the
  // `React.memo` on the tiles is decorative.
  const handlePasteLink = useCallback(() => void pasteLink(), [pasteLink]);

  /** Switch the panel to the inline note editor. */
  const handleOpenNote = useCallback(() => {
    setError(null);
    setNoteTitle('');
    setNoteText('');
    setMode('note');
  }, []);

  /**
   * Opens the system image picker (gallery), uploads the selected image to
   * Supabase Storage, then creates an IMAGE save that Gemini will analyse.
   *
   * Flow:
   *   1. Request media library permission (auto-granted on first share).
   *   2. Let user pick one image from the gallery.
   *   3. Upload to `screenshots` bucket as `{userId}/{uuid}.jpg`.
   *   4. POST /v1/saves with sourceType=image + sourceUrl = public URL.
   */
  const handleOpenScreenshot = useCallback(async () => {
    const { status } = await ImagePicker.requestMediaLibraryPermissionsAsync();
    if (status !== 'granted') {
      Alert.alert('Permission needed', 'Allow Weavr to access your photos to save screenshots.');
      return;
    }

    const result = await ImagePicker.launchImageLibraryAsync({
      mediaTypes: 'images' as const,
      quality: 0.85,
      allowsEditing: false,
      allowsMultipleSelection: false,
    });

    if (result.canceled || result.assets.length === 0) return;

    const asset = result.assets[0];
    const fileName = `${Date.now()}.jpg`;

    // Read the file as a Blob for Supabase Storage upload
    const response = await fetch(asset.uri);
    const blob = await response.blob();

    const { data: session } = await supabase.auth.getSession();
    const userId = session?.session?.user?.id ?? 'anonymous';
    const storagePath = `${userId}/${fileName}`;

    const { error: uploadError } = await supabase.storage
      .from('screenshots')
      .upload(storagePath, blob, { contentType: 'image/jpeg', upsert: true });

    if (uploadError) {
      haptic('error');
      setError('Upload failed. Try again.');
      console.warn('[screenshot] upload error:', uploadError.message);
      return;
    }

    const supabaseBase = SUPABASE_URL.replace(/\/$/, '');
    const publicUrl = `${supabaseBase}/storage/v1/object/public/screenshots/${storagePath}`;

    haptic('success');
    dismiss();

    void repo
      .createSave({ sourceType: 'image', sourceUrl: publicUrl })
      .then(prepend)
      .catch((e: unknown) => {
        haptic('error');
        console.warn('[screenshot] save failed:', e instanceof ApiError ? e.message : e);
      });
  }, [dismiss, haptic, prepend]);

  const handleOpenScreenshotPress = useCallback(
    () => void handleOpenScreenshot(),
    [handleOpenScreenshot],
  );

  /**
   * Save the typed note and dismiss, mirroring pasteLink's fire-and-forget
   * pattern: validate locally → haptic → dismiss → POST in background.
   * The sheet is never held open waiting on the network.
   */
  const handleSaveNote = useCallback(async () => {
    const body = noteText.trim();
    if (!body) {
      haptic('error');
      setError('Write something first.');
      return;
    }
    const title = noteTitle.trim() || undefined;
    haptic('success');
    setMode('tiles');
    setNoteTitle('');
    setNoteText('');
    dismiss();

    void repo
      .createSave({ sourceType: 'text', text: body, title })
      .then(prepend)
      .catch((e: unknown) => {
        haptic('error');
        console.warn('[capture] note save failed:', e instanceof ApiError ? e.message : e);
      });
  }, [noteTitle, noteText, dismiss, haptic, prepend]);

  const handleSaveNotePress = useCallback(() => void handleSaveNote(), [handleSaveNote]);

  // Static input, so this must not be rebuilt on every keystroke of state.
  const rows = useMemo(() => chunk(CAPTURE_OPTIONS, COLUMNS), []);

  return (
    <KeyboardAvoidingView
      style={{ flex: 1, justifyContent: 'flex-end' }}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      keyboardVerticalOffset={0}
    >
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
        The panel grows out of the FAB on the same `progress` spring that drives
        the backdrop, so expansion and dismissal are one motion rather than two
        animations that merely happen to overlap.

        `transformOrigin` is what turns a scale into an expansion: pinned to the
        FAB's centre on x and the panel's own bottom edge on y, the surface
        appears to open from the button that was pressed. Scaled about its own
        centre instead, the same animation reads as a card being zoomed —
        correct motion, wrong story.
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
            transformOrigin: [fabCenterX, '100%', 0],
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

        {mode === 'note' ? (
          /*
           * Inline note editor — replaces the tile grid when the user taps
           * "Text Note". No navigation, no new route: the panel content swaps
           * in place so the sheet entrance/exit animation is unchanged.
           */
          <View>
            {/* Back navigation */}
            <Pressable
              onPress={() => { setMode('tiles'); setError(null); }}
              style={{ flexDirection: 'row', alignItems: 'center', marginBottom: spacing.lg }}
              accessibilityRole="button"
              accessibilityLabel="Back to capture options"
            >
              <Glyph name="chevron" size={16} weight={2.5} color={palette.accent} />
              <AppText style={{ color: palette.accent, fontSize: 14, marginLeft: 4 }}>
                Text note
              </AppText>
            </Pressable>

            {/* Title — single line, auto-focused */}
            <TextInput
              value={noteTitle}
              onChangeText={(t) => { setNoteTitle(t); setError(null); }}
              placeholder="Title"
              placeholderTextColor={palette.textFaint}
              autoFocus
              returnKeyType="next"
              maxLength={500}
              style={{
                fontSize: 18,
                fontWeight: '600',
                color: palette.text,
                backgroundColor: palette.surfaceVariant,
                borderRadius: radius.md,
                borderWidth: 1,
                borderColor: palette.border,
                paddingHorizontal: spacing.md,
                paddingVertical: spacing.sm + 2,
                marginBottom: spacing.sm,
              }}
            />

            {/* Body — multiline */}
            <TextInput
              value={noteText}
              onChangeText={(t) => { setNoteText(t); setError(null); }}
              placeholder="Write your note…"
              placeholderTextColor={palette.textFaint}
              multiline
              maxLength={100_000}
              textAlignVertical="top"
              style={{
                minHeight: 110,
                fontSize: 15,
                lineHeight: 22,
                color: palette.text,
                backgroundColor: palette.surfaceVariant,
                borderRadius: radius.md,
                borderWidth: 1,
                borderColor: error ? palette.danger : palette.border,
                padding: spacing.md,
              }}
            />

            {/* Counter + error + save */}
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginTop: spacing.md,
              }}
            >
              <AppText variant="caption" style={{ color: error ? palette.danger : palette.textFaint, fontSize: 12, flex: 1 }}>
                {error ?? `${noteText.length.toLocaleString()} / 100,000`}
              </AppText>
              <Touchable
                onPress={handleSaveNotePress}
                weight="control"
                style={{
                  paddingVertical: spacing.sm + 2,
                  paddingHorizontal: spacing.lg,
                  borderRadius: radius.pill,
                  backgroundColor: palette.accent,
                }}
              >
                <AppText variant="navLabel" style={{ color: palette.onAccent }}>
                  Save note
                </AppText>
              </Touchable>
            </View>
          </View>
        ) : (
          /*
           * Default tile grid.
           */
          <>
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
                      tilesOut={tilesOut}
                      // Stable identities, or `React.memo` on the tile buys nothing
                      // — a fresh arrow per render makes every tile re-render on
                      // every keystroke of sheet state.
                      onPress={
                        option.id === 'link'       ? handlePasteLink :
                        option.id === 'note'       ? handleOpenNote :
                        option.id === 'screenshot' ? handleOpenScreenshotPress :
                        noop
                      }
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
                Paste Link, Text Note, and Screenshot are wired up. The rest arrive with their capture surfaces.
              </AppText>
            )}
          </>
        )}
      </Animated.View>
    </KeyboardAvoidingView>
  );
}
