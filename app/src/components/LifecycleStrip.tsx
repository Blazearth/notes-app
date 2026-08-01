import React, { useState } from 'react';
import { View } from 'react-native';

import { repo } from '@/data';
import type { LifecycleStatus } from '@/api/types';
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
  // Optimistic, and reverted on failure. A tap here should feel instant; it is
  // one small column on one row, so the cost of being wrong for a moment is
  // far lower than the cost of a laggy control.
  const [local, setLocal] = useState<LifecycleStatus>(value);
  const [busy, setBusy] = useState(false);

  const select = async (next: LifecycleStatus) => {
    if (busy || next === local) return;
    const previous = local;
    setLocal(next);
    setBusy(true);
    try {
      const updated = await repo.setSaveLifecycle(saveId, next);
      // Trust the server's echo rather than the optimistic guess.
      setLocal(updated.lifecycleStatus ?? next);
      onChange?.(updated.lifecycleStatus ?? next);
    } catch {
      setLocal(previous);
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel>Progress</SectionLabel>
      <View style={{ flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' }}>
        {STEPS.map((step) => {
          const active = step.value === local;
          return (
            <Touchable
              key={step.value}
              accessibilityRole="radio"
              accessibilityState={{ selected: active, disabled: busy }}
              accessibilityLabel={step.label}
              onPress={() => void select(step.value)}
              haptic="selection"
              style={{
                paddingVertical: spacing.xs + 3,
                paddingHorizontal: spacing.md,
                borderRadius: radius.sm,
                borderWidth: 1,
                borderColor: active ? palette.accent : palette.border,
                backgroundColor: active ? palette.accent : palette.surface,
                opacity: busy ? 0.6 : 1,
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
