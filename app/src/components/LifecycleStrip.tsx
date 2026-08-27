import React from 'react';
import { View } from 'react-native';

import type { LifecycleStatus } from '@/api/types';
import { writeSaveLifecycle } from '@/local/writes';
import { SectionLabel } from '@/components/SectionLabel';
import { Segmented } from '@/components/Segmented';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * `saved → planned → started → completed`, as one travelling-thumb segmented
 * control (`Segmented`, the same one Spaces' own tab strip uses) rather than
 * four separate pill buttons — the four states are one progression, not four
 * unrelated choices, and the thumb sliding between them shows that directly.
 *
 * Still not a strict forward-only stepper: the server does not treat this as
 * a state machine, and going backward (un-starting something) is an ordinary
 * thing to want, so every segment stays reachable from every other.
 */

const STEPS: { value: LifecycleStatus; label: string }[] = [
  { value: 'saved', label: 'Saved' },
  { value: 'planned', label: 'Planned' },
  { value: 'started', label: 'Started' },
  { value: 'completed', label: 'Done' },
];

export function LifecycleStrip({
  saveId,
  value,
  onChange,
}: {
  saveId: string;
  value: LifecycleStatus;
  onChange?: (next: LifecycleStatus) => void;
}) {
  const { spacing } = useTheme();
  // No local mirror of the value any more, and no `busy` flag.
  //
  // `value` comes from the save in the local store, which the write below
  // updates before the request is even queued — so the strip re-renders from the
  // real source of truth on the same frame as the tap, and there is nothing to
  // keep in step, nothing to roll back, and no reason to disable the control
  // while a request is in flight.
  const select = (next: LifecycleStatus) => {
    if (next === value) return;
    writeSaveLifecycle(saveId, next);
    onChange?.(next);
  };

  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel>Progress</SectionLabel>
      <Segmented options={STEPS} value={value} onChange={select} />
    </View>
  );
}
