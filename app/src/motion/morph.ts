/**
 * Shared state for the container-transform navigation (Home → Settings).
 *
 * Two screens have to agree on one animation: the Settings surface grows out of
 * the button that opened it, and the shell underneath recedes on the *same*
 * curve at the *same* time. Anything less than a single driving value gives two
 * animations that start together and drift — which is exactly what makes a
 * transition read as two pages rather than one surface changing state.
 *
 * So the value lives here, at module scope, rather than in a provider:
 *
 * - It is read on the UI thread by both screens. A React context would deliver
 *   it as a prop and re-render to change it; a shared value is mutated in place
 *   and read inside worklets, so the whole morph runs without touching JS.
 * - The two screens are on opposite sides of a navigation boundary. The route
 *   below stays mounted (the modal is transparent), but it is not a parent, so
 *   there is no component that could own the state for both.
 * - Only one morph can be in flight at a time — there is one navigation stack —
 *   so a singleton is not a limitation being accepted, it is the actual shape.
 */

import { makeMutable } from 'react-native-reanimated';
import type { View } from 'react-native';

/** The on-screen rectangle a morph grows out of, in window coordinates. */
export interface MorphOrigin {
  x: number;
  y: number;
  width: number;
  height: number;
  /** The control's own corner radius, so the surface starts as *that* shape. */
  radius: number;
}

/**
 * "We never measured a source." Zero width is the sentinel rather than `null`
 * because worklets read this every frame and a nullable shape would need a
 * branch in each of them; `width > 0` is the one check, made once per style.
 */
export const NO_MORPH_ORIGIN: MorphOrigin = { x: 0, y: 0, width: 0, height: 0, radius: 0 };

/**
 * 0 = collapsed into the source control, 1 = fully expanded.
 *
 * Driven by the presented screen, read by the presenting one.
 */
export const morphProgress = makeMutable(0);

/** Where the current morph starts and ends up returning to. */
export const morphOrigin = makeMutable<MorphOrigin>(NO_MORPH_ORIGIN);

/**
 * Measure a control, record it as the morph origin, and only then navigate.
 *
 * The ordering is the point. `measureInWindow` is asynchronous, and the
 * presented screen reads the origin in its first `useAnimatedStyle` — navigate
 * first and the surface expands from a stale rectangle on the very first open,
 * which is the one that matters most.
 *
 * Measured at press time rather than on layout because the Home header sits in
 * a `ScrollView`: its window position is a function of scroll offset, so a
 * value cached at mount is wrong the moment the user scrolls.
 */
export function morphFrom(
  // Structural rather than `RefObject<View>`: this is called with whatever
  // `useRef` produced, and the exact ref type has moved between React versions.
  anchor: { current: View | null },
  radius: number,
  navigate: () => void,
): void {
  const node = anchor.current;
  if (!node) {
    morphOrigin.value = NO_MORPH_ORIGIN;
    navigate();
    return;
  }

  // A tap must never be swallowed by a measurement that does not come back —
  // a detached or zero-sized view can leave the callback unfired, and the
  // failure mode would be a Settings button that silently does nothing. If the
  // measurement misses its window, navigate anyway and let the screen fall back
  // to its unanchored entrance.
  let navigated = false;
  const go = (origin: MorphOrigin) => {
    if (navigated) return;
    navigated = true;
    morphOrigin.value = origin;
    navigate();
  };

  const timeout = setTimeout(() => go(NO_MORPH_ORIGIN), 48);

  node.measureInWindow((x, y, width, height) => {
    clearTimeout(timeout);
    go(
      width > 0 && height > 0
        ? { x, y, width, height, radius }
        : NO_MORPH_ORIGIN,
    );
  });
}
