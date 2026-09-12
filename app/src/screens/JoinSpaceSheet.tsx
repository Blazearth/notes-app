import * as Clipboard from 'expo-clipboard';
import { useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ActivityIndicator, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { repo } from '@/data';
import { getStore } from '@/local';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Sheet } from '@/components/Sheet';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * A pasted invite is sometimes a bare code and sometimes a full link wrapping
 * one (`.../i/<code>`) — accept either rather than making the user strip the
 * link down themselves. No deep-link route resolves an invite URL yet, so this
 * is the one place that shape is understood; if a link route is added later,
 * it should call the same `acceptInvite` this does rather than duplicate it.
 */
function extractCode(pasted: string): string {
  const trimmed = pasted.trim();
  try {
    const url = new URL(trimmed);
    const segments = url.pathname.split('/').filter(Boolean);
    return segments[segments.length - 1] ?? trimmed;
  } catch {
    return trimmed;
  }
}

export function JoinSpaceSheet() {
  const { palette, radius, spacing, alpha } = useTheme();
  const router = useRouter();

  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onJoin = useCallback(
    async (dismiss: () => void) => {
      const trimmed = extractCode(code);
      if (!trimmed || busy) return;
      setBusy(true);
      setError(null);
      try {
        const space = await repo.acceptInvite(trimmed);
        // Same reason as CreateSpaceSheet: the list behind this sheet reads the
        // store, so writing here is what makes the join visible immediately.
        await getStore().putSpaces([space]);
        track(AnalyticsEvent.SpaceJoined, {});
        dismiss();
        router.push({ pathname: '/space/[id]', params: { id: space.id } });
      } catch (e) {
        // 404 covers expired, revoked and used-up alike — the server does not
        // distinguish them, and neither should this.
        setError(e instanceof ApiError ? e.message : 'That invite link did not work.');
        setBusy(false);
      }
    },
    [code, busy, router],
  );

  const onPasteFromClipboard = useCallback(async () => {
    const clipboard = await Clipboard.getStringAsync();
    if (clipboard.trim()) setCode(clipboard.trim());
  }, []);

  const canJoin = !!code.trim() && !busy;

  return (
    <Sheet title="Join a Space" subtitle="Paste an invite code or link someone shared with you.">
      {({ dismiss }) => (
        <View>
          <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'center', marginBottom: spacing.lg }}>
            <TextInput
              value={code}
              onChangeText={setCode}
              placeholder="Paste an invite code"
              placeholderTextColor={palette.textFaint}
              autoCapitalize="none"
              autoCorrect={false}
              returnKeyType="go"
              onSubmitEditing={() => void onJoin(dismiss)}
              editable={!busy}
              style={{
                flex: 1,
                color: palette.text,
                backgroundColor: palette.surface,
                borderWidth: 1,
                borderColor: palette.border,
                borderRadius: radius.sm,
                paddingHorizontal: spacing.md,
                paddingVertical: spacing.smd,
                fontSize: 14,
              }}
            />
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Paste from clipboard"
              onPress={onPasteFromClipboard}
              haptic="selection"
              weight="tile"
              style={{
                width: 42,
                height: 42,
                borderRadius: radius.sm,
                borderWidth: 1,
                borderColor: palette.border,
                backgroundColor: palette.surface,
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Glyph name="download" size={16} color={palette.textMuted} />
            </Touchable>
          </View>

          {error ? (
            <AppText variant="caption" style={{ color: palette.danger, marginBottom: spacing.md }}>
              {error}
            </AppText>
          ) : null}

          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Join Space"
            accessibilityState={{ disabled: !canJoin }}
            disabled={!canJoin}
            onPress={() => void onJoin(dismiss)}
            haptic="medium"
            baseOpacity={canJoin ? 1 : 0.5}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              gap: spacing.sm,
              paddingVertical: spacing.md,
              borderRadius: radius.sm,
              backgroundColor: palette.accent,
              marginBottom: spacing.xl,
            }}
          >
            {busy ? (
              <ActivityIndicator color={palette.onAccent} size="small" />
            ) : (
              <AppText style={{ color: palette.onAccent, fontWeight: '600' }}>Join Space</AppText>
            )}
          </Touchable>

          {/* No camera capture surface exists anywhere in the app yet (see
              CaptureSheet — 11 of 12 tiles are the same kind of placeholder),
              so this stays a dimmed preview of where QR joining will live
              rather than a half-built scanner. */}
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing.md,
              opacity: alpha.disabled,
            }}
          >
            <View
              style={{
                width: 36,
                height: 36,
                borderRadius: radius.sm,
                backgroundColor: palette.surfaceVariant,
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Glyph name="qrCode" size={16} color={palette.textMuted} />
            </View>
            <AppText variant="caption" tone="muted">
              Scan QR code — arrives with camera capture
            </AppText>
          </View>
        </View>
      )}
    </Sheet>
  );
}
