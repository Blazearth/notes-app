import React, { useCallback } from 'react';
import { Modal, Pressable, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { SaveResponse, Space } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Touchable } from '@/components/Touchable';
import { useLiveValue } from '@/local';
import { writeSaveSpace } from '@/local/writes';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for "the store has no Spaces" — see `useLiveValue`. */
const EMPTY_SPACES: Space[] = [];

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
  // From the store, so the picker is populated the instant the sheet opens —
  // this used to be a `repo.listSpaces()` on mount, which meant a spinner over a
  // list the app already had, and an empty picker offline.
  const spaces = useLiveValue<Space[]>(['spaces'], (store) => store.readSpaces(), EMPTY_SPACES);

  const apply = useCallback(
    (spaceId: string | null) => {
      // Local first, queued second. The sheet can close immediately because the
      // move has already happened as far as every screen is concerned — there is
      // no request to wait on, no busy state to show and nothing to revert.
      writeSaveSpace(save.id, spaceId);
      onClose();
    },
    [save.id, onClose],
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
          {/* No loading branch: the Spaces come from the store, so they are
              either there on the first frame or the user genuinely has none. */}
          {spaces.length === 0 ? (
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
                onPress={() => apply(null)}
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
                  onPress={active ? undefined : () => apply(space.id)}
                />
              );
            })}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}

function SpaceRow({
  label, color, glyph, active, onPress,
}: {
  label: string; color: string; glyph: string;
  active: boolean; onPress?: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: active }}
      onPress={onPress}
      haptic="selection"
      disabled={active}
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
      {active ? <Glyph name="check" size={icon.sm} weight={2.5} color={color} /> : null}
    </Touchable>
  );
}
