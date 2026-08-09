import React from 'react';
import { View } from 'react-native';

import type { LifecycleStatus } from '@/api/types';
import { writeSaveLifecycle } from '@/local/writes';
import { AppText } from '@/components/AppText';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * `saved → planned → started → completed`, as four taps.
 *
 * <p>Rendered as a row of states rather than a "next step" button because the
 * server does not treat this as a state machine — going backwards is an
 * ordinary thing to want, and a UI that only advances would be stricter than
 * the thing it is driving.
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
  const { palette, radius, spacing } = useTheme();
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
      <View style={{ flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' }}>
        {STEPS.map((step) => {
          const active = step.value === value;
          return (
            <Touchable
              key={step.value}
              accessibilityRole="radio"
              accessibilityState={{ selected: active }}
              accessibilityLabel={step.label}
              onPress={() => select(step.value)}
              haptic="selection"
              style={{
                paddingVertical: spacing.xs + 3,
                paddingHorizontal: spacing.md,
                borderRadius: radius.sm,
                borderWidth: 1,
                borderColor: active ? palette.accent : palette.border,
                backgroundColor: active ? palette.accent : palette.surface,
              }}
            >
              <AppText
                variant="caption"
                style={{ color: active ? palette.onAccent : palette.textMuted }}
              >
                {step.label}
              </AppText>
            </Touchable>
          );
        })}
      </View>
    </View>
  );
}
