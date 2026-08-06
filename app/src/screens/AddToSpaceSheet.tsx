import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import type { SaveResponse, Space } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Touchable } from '@/components/Touchable';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { useSaves } from '@/saves/SavesProvider';
import { useTheme } from '@/theme/ThemeProvider';

interface AddToSpaceSheetProps {
  save: SaveResponse;
  onClose: () => void;
}

/**
 * Bottom-sheet modal for moving a save into a Space or back to private feed.
 * Uses a plain RN Modal so it can be rendered inline without a route change.
 */
export function AddToSpaceSheet({ save, onClose }: AddToSpaceSheetProps) {
  const { palette, radius, spacing, elevation } = useTheme();
  const insets = useSafeAreaInsets();
  const { patch } = useSaves();
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    repo.listSpaces().then(setSpaces).catch(() => setSpaces([])).finally(() => setLoading(false));
  }, []);

  const apply = useCallback(
    async (spaceId: string | null) => {
      if (busy) return;
      setBusy(spaceId ?? '__private__');
      setError(null);
      patch(save.id, { spaceId: spaceId ?? undefined });
      try {
        const updated = await repo.setSaveSpace(save.id, spaceId);
        patch(save.id, updated);
        onClose();
      } catch (e) {
        patch(save.id, { spaceId: save.spaceId });
        setError(e instanceof ApiError ? e.message : 'Could not move that save.');
        setBusy(null);
      }
    },
    [busy, save.id, save.spaceId, patch, onClose],
  );

  return (
    <Modal transparent animationType="slide" onRequestClose={onClose}>
      {/* Backdrop */}
      <Pressable
        style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' }}
        onPress={onClose}
      />
      {/* Sheet */}
      <View
        style={[
          {
            backgroundColor: palette.surface,
            borderTopLeftRadius: radius.xl ?? 20,
            borderTopRightRadius: radius.xl ?? 20,
            paddingHorizontal: spacing.lg,
            paddingTop: spacing.lg,
            paddingBottom: insets.bottom + spacing.lg,
            maxHeight: '80%',
          },
          elevation.card,
        ]}
      >
        {/* Handle */}
        <View
          style={{
            width: 36,
            height: 4,
            borderRadius: 2,
            backgroundColor: palette.border,
            alignSelf: 'center',
            marginBottom: spacing.lg,
          }}
        />

        <AppText variant="title" style={{ marginBottom: spacing.xs }}>
          Add to Space
        </AppText>
        <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.xl }}>
          Move this save into a shared Space, or keep it private.
        </AppText>

        <ScrollView showsVerticalScrollIndicator={false} style={{ flexGrow: 0 }}>
          {loading ? (
            <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
              <ActivityIndicator color={palette.accent} />
            </View>
          ) : null}

          {!loading && spaces.length === 0 ? (
            <AppText variant="caption" tone="muted">
              You don't have any Spaces yet. Create one from the Spaces tab.
            </AppText>
          ) : null}

          <View style={{ gap: spacing.sm }}>
            {/* Remove from space option */}
            {save.spaceId ? (
              <SpaceRow
                label="My private feed"
                color={palette.textFaint}
                glyph="layers"
                active={false}
                busy={busy === '__private__'}
                onPress={() => void apply(null)}
              />
            ) : null}

            {spaces.map((space) => {
              const identity = spaceIdentity(space);
              const active = save.spaceId === space.id;
              return (
                <SpaceRow
                  key={space.id}
                  label={space.name}
                  color={identity.color}
                  glyph={identity.glyph}
                  active={active}
                  busy={busy === space.id}
                  onPress={active ? undefined : () => void apply(space.id)}
                />
              );
            })}
          </View>

          {error ? (
            <AppText variant="caption" style={{ color: palette.danger, marginTop: spacing.md }}>
              {error}
            </AppText>
          ) : null}
        </ScrollView>
      </View>
    </Modal>
  );
}

function SpaceRow({
  label, color, glyph, active, busy, onPress,
}: {
  label: string; color: string; glyph: string;
  active: boolean; busy: boolean; onPress?: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: active }}
      onPress={onPress}
      haptic="selection"
      disabled={active || busy}
      baseOpacity={active ? 0.6 : 1}
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.md,
        padding: spacing.md,
        borderRadius: radius.md,
        borderWidth: 1,
        borderColor: active ? color : palette.border,
        backgroundColor: active ? `${color}1A` : palette.surface,
      }}
    >
      <View
        style={{
          width: 34, height: 34,
          borderRadius: radius.sm,
          backgroundColor: `${color}26`,
          alignItems: 'center', justifyContent: 'center',
        }}
      >
        <Glyph name={glyph as any} size={16} weight={2} color={color} />
      </View>
      <AppText variant="cardTitle" style={{ flex: 1 }}>{label}</AppText>
      {busy ? (
        <ActivityIndicator size="small" color={color} />
      ) : active ? (
        <Glyph name="check" size={icon.sm} weight={2.5} color={color} />
      ) : null}
    </Touchable>
  );
}
