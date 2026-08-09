import React from 'react';
import { View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { describeOp } from '@/local/outbox';
import { useOutbox } from '@/local/useSync';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * The outbox, made visible — and the reason `@/local/writes` is allowed to have
 * no rollback logic.
 *
 * A write the server rejects outright (a 400, a 403, a 404, a cap the user has to
 * act on) cannot be retried into success. The tempting answer is to quietly put
 * the old value back, and it is the wrong one: the app has already shown the
 * change as done, so silently undoing it is indistinguishable from "your tap
 * never registered" — which is exactly the failure mode the whole local-first
 * layer exists to remove. So a terminal failure is reported, with the two things
 * a person can actually do about it.
 *
 * **Retry** clears the terminal status and sends again, keeping the entry's
 * position and its idempotency key — so a write that failed because of something
 * since fixed (a Space role granted, a subscription started) goes through
 * unchanged. **Discard** drops the write *and* re-reads the server's truth for
 * whatever it was about; dropping alone would leave the store showing a value
 * nobody will ever agree with.
 *
 * Renders nothing in the ordinary case, which is almost always. It lives on
 * Settings rather than floating over every screen because a queued write is not
 * an error — the point of the queue is that the user does not have to watch it.
 */
export function PendingWrites() {
  const { spacing } = useTheme();
  const { pending, failed, paused } = useOutbox();

  if (failed.length === 0 && !paused && pending.length === 0) return null;

  return (
    <View style={{ marginBottom: spacing.xxl }}>
      <SectionLabel>Unsent changes</SectionLabel>

      {/* Not a failure: nothing was rejected, the session just needs renewing,
          and the queue moves again by itself when it does. */}
      {paused ? (
        <Card style={{ marginBottom: spacing.sm }}>
          <AppText variant="caption" tone="muted">
            Waiting to sign in again before sending your changes.
          </AppText>
        </Card>
      ) : null}

      {/* Deliberately a count rather than a list. A write on its way is normal
          and momentary; enumerating them would invite watching a queue whose
          whole purpose is not needing to be watched. */}
      {!paused && pending.length > 0 ? (
        <Card style={{ marginBottom: spacing.sm }}>
          <AppText variant="caption" tone="muted">
            {pending.length === 1
              ? 'Sending 1 change…'
              : `Sending ${pending.length} changes…`}
          </AppText>
        </Card>
      ) : null}

      {failed.map((entry) => (
        <FailedWrite key={entry.id} id={entry.id} label={describeOp(entry.op)} reason={entry.lastError} />
      ))}

      {failed.length > 0 ? (
        <AppText variant="caption" tone="muted" style={{ marginTop: spacing.xs }}>
          These changes are saved on this device but were turned down by the server.
        </AppText>
      ) : null}
    </View>
  );
}

function FailedWrite({
  id,
  label,
  reason,
}: {
  id: number;
  label: string;
  reason: string | null;
}) {
  const { palette, radius, spacing } = useTheme();
  const { retry, discard } = useOutbox();

  return (
    <Card style={{ marginBottom: spacing.sm, borderColor: palette.danger }}>
      <AppText variant="cardTitle">{label}</AppText>
      {reason ? (
        <AppText variant="caption" tone="muted" style={{ marginTop: 2 }} numberOfLines={3}>
          {reason}
        </AppText>
      ) : null}
      <View style={{ flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={`Try ${label} again`}
          onPress={() => retry(id)}
          haptic="selection"
          style={{
            paddingVertical: spacing.xs + 3,
            paddingHorizontal: spacing.md,
            borderRadius: radius.sm,
            backgroundColor: palette.accent,
          }}
        >
          <AppText variant="caption" style={{ color: palette.onAccent }}>
            Try again
          </AppText>
        </Touchable>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={`Discard ${label}`}
          onPress={() => discard(id)}
          haptic="selection"
          style={{
            paddingVertical: spacing.xs + 3,
            paddingHorizontal: spacing.md,
            borderRadius: radius.sm,
            borderWidth: 1,
            borderColor: palette.border,
          }}
        >
          <AppText variant="caption" tone="muted">
            Discard
          </AppText>
        </Touchable>
      </View>
    </Card>
  );
}
