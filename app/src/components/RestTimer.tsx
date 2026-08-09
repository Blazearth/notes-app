import React, { useEffect, useMemo, useState } from 'react';

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
  const parsedSeconds = useMemo(() => {
    const match = label.match(/(\d+(?:\.\d+)?)\s*(s|sec|second|m|min|minute)/i);
    if (!match) return null;
    const value = parseFloat(match[1]);
    return Math.round(match[2].toLowerCase().startsWith('m') ? value * 60 : value);
  }, [label]);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [manualElapsed, setManualElapsed] = useState<number | null>(null);

  useEffect(() => {
    if (remaining === null || remaining <= 0) return;
    const t = setTimeout(() => setRemaining((r) => (r !== null ? r - 1 : r)), 1000);
    return () => clearTimeout(t);
  }, [remaining]);

  useEffect(() => {
    if (manualElapsed === null) return;
    const t = setTimeout(() => setManualElapsed((e) => (e !== null ? e + 1 : e)), 1000);
    return () => clearTimeout(t);
  }, [manualElapsed]);

  const running = remaining !== null || manualElapsed !== null;
  const start = () => (parsedSeconds !== null ? setRemaining(parsedSeconds) : setManualElapsed(0));
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
            : `Rest ${label}`}
      </AppText>
    </Touchable>
  );
}
