/**
 * The motion language.
 *
 * Three springs and three press depths, reused everywhere, so that every
 * surface in the app accelerates and settles the same way. The point is not
 * that any single value is special — it is that a card, a chip and a nav tab
 * all answer a touch with the *same* curve, which is what reads as deliberate
 * rather than decorative.
 *
 * Springs, not durations, for anything a finger drives. A duration has to
 * finish even when the user has already moved on; a spring can be retargeted
 * mid-flight, so a fast double-tap looks like one continuous motion instead of
 * two queued animations. `Duration` in `tokens.ts` stays for the things that
 * genuinely are time-based — fades and cross-dissolves.
 */

/** Physics for `withSpring`. Higher stiffness = quicker; higher damping = less overshoot. */
export const Spring = {
  /**
   * Press in / out. Deliberately stiff and slightly overdamped: this fires on
   * every tap in the app, so it must feel immediate and must never wobble.
   */
  press: { damping: 20, stiffness: 450, mass: 0.5 },
  /**
   * An indicator travelling to a new position — the nav pill, the segmented
   * thumb. Loose enough to read as a physical slide, damped enough not to
   * bounce past a neighbouring label and back.
   */
  travel: { damping: 24, stiffness: 240, mass: 0.9 },
  /**
   * Something arriving on screen — the capture sheet. The softest of the three,
   * because an entrance is the one place a little overshoot reads as "landed"
   * rather than as imprecision.
   */
  enter: { damping: 22, stiffness: 190, mass: 1 },
  /**
   * A surface growing out of the control that opened it — the Settings morph.
   *
   * Deliberately the loosest spring in the set: ζ ≈ 0.71, so it overshoots by a
   * few percent and settles rather than arriving flat. That overshoot is the
   * whole point at this size — a full-screen surface travelling several hundred
   * points on a critically damped curve reads as a mechanical wipe, where the
   * same distance with a little give reads as weight.
   *
   * The *same* config drives the collapse, so dismissal is the entrance played
   * backwards rather than a second, differently-tuned animation.
   */
  morph: { damping: 18, stiffness: 160, mass: 1 },
} as const;

/**
 * How far a surface dips under a finger, by how big it is.
 *
 * Scale is perceptual, not absolute: a full-width card shifted 4% looks broken,
 * while a 56px tile shifted 4% looks inert. Large surfaces move least.
 */
export const PressDepth = {
  /** Cards and list rows — full-bleed surfaces. */
  card: 0.985,
  /** Chips, tabs, list links — the default. */
  control: 0.96,
  /** Icon tiles and the FAB, where the whole target is the affordance. */
  tile: 0.92,
} as const;

export type PressWeight = keyof typeof PressDepth;

/**
 * Opacity lost at full press, on top of the scale.
 *
 * Small on purpose. Scale carries the feedback; this only stops the dip from
 * looking like the surface is floating *toward* the finger.
 */
export const PRESS_DIM = 0.08;

/** Per-item delay when a group of items enters together. */
export const Stagger = {
  step: 45,
  /**
   * Past this many items the stagger stops accumulating. Without a cap the
   * last row of a long list arrives almost a second late, which stops being
   * choreography and starts being lag.
   */
  max: 6,
} as const;

/** Delay for the `index`-th item of a staggered group, in ms. */
export function staggerDelay(index: number): number {
  return Math.min(index, Stagger.max) * Stagger.step;
}
