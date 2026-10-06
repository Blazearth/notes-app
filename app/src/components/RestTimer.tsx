import React, { useEffect, useMemo, useState } from 'react';

import { useHaptic } from '@/motion/haptics';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

/**
 * A workout exercise's rest timer. Parses the extracted `rest` text leniently
 * ("90s", "2 min"); when it doesn't parse, falls back to a manual up-counting
 * timer rather than showing nothing — `docs/next-phases.md` §4.2.
 *
 * Purely local: the countdown is never written to `save_item_states` or
 * `entity_states`. A timer is a thing happening now, not a fact about the
 * library, and persisting it would resume a rest period on a device the user
 * picked up two days later.
 *
 * Lives here rather than inside `SaveDetailScreen` because the workout
 * session screen runs the same timer over a *merged* exercise — same
 * behaviour, two callers, and duplicating the parse is how the two would
 * drift on the first "1m30s" nobody handled.
 */
export function RestTimer({ label }: { label: string }) {
  const { palette, spacing } = useTheme();
  const haptic = useHaptic();
  // Every `<number><unit>` in the label, summed — "1m30s" is 90, not the 60 a
  // first-match parse read it as. The unit must not run on into a word, so the
  // "m" of "max" is not a minute.
  const parsedSeconds = useMemo(() => {
    const parts = [...label.matchAll(/(\d+(?:\.\d+)?)\s*(seconds?|secs?|s|minutes?|mins?|m)(?![a-z])/gi)];
    if (parts.length === 0) return null;
    const total = parts.reduce((sum, [, value, unit]) => {
      const n = parseFloat(value);
      return sum + (unit.toLowerCase().startsWith('m') ? n * 60 : n);
    }, 0);
    return total > 0 ? Math.round(total) : null;
  }, [label]);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [manualElapsed, setManualElapsed] = useState<number | null>(null);
  const [justFinished, setJustFinished] = useState(false);

  useEffect(() => {
    if (remaining === null) return;
    if (remaining <= 0) {
      // Rest is over: say so once, then return to the idle label rather than
      // sitting at "0s left" until someone taps it.
      haptic('success');
      setRemaining(null);
      setJustFinished(true);
      return;
    }
    const t = setTimeout(() => setRemaining((r) => (r !== null ? r - 1 : r)), 1000);
    return () => clearTimeout(t);
  }, [remaining, haptic]);

  useEffect(() => {
    if (!justFinished) return;
    const t = setTimeout(() => setJustFinished(false), 4000);
    return () => clearTimeout(t);
  }, [justFinished]);

  useEffect(() => {
    if (manualElapsed === null) return;
    const t = setTimeout(() => setManualElapsed((e) => (e !== null ? e + 1 : e)), 1000);
    return () => clearTimeout(t);
  }, [manualElapsed]);

  const running = remaining !== null || manualElapsed !== null;
  const start = () => {
    setJustFinished(false);
    if (parsedSeconds !== null) setRemaining(parsedSeconds);
    else setManualElapsed(0);
  };
  const stop = () => {
    setRemaining(null);
    setManualElapsed(null);
  };

  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={running ? 'Stop rest timer' : `Start rest timer, ${label}`}
      onPress={running ? stop : start}
      haptic="light"
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginTop: spacing.xs }}
    >
      <Glyph name="clock" size={14} color={running ? palette.accent : palette.textMuted} />
      <AppText variant="bodySmall" tone={running ? 'accent' : 'muted'}>
        {remaining !== null
          ? `${remaining}s left`
          : manualElapsed !== null
            ? `${manualElapsed}s`
            : justFinished
              ? 'Rest done'
              : `Rest ${label}`}
      </AppText>
    </Touchable>
  );
}
