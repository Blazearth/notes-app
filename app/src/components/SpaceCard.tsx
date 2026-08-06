import React from 'react';
import { View } from 'react-native';

import type { SaveResponse, Space, SpaceMember } from '@/api/types';
import { relativeTime, saveTitle } from '@/saves/format';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { AvatarStack } from './Avatar';
import { Card } from './Card';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

function RoleBadge({ role }: { role: Space['myRole'] }) {
  const { palette, radius, spacing } = useTheme();
  // Only worth showing when it constrains what you can do. Labelling every
  // owner "owner" in their own list is noise.
  if (role === 'owner') return null;
  return (
    <View
      style={{
        borderRadius: radius.xs,
        borderWidth: 1,
        borderColor: palette.border,
        paddingHorizontal: spacing.xs + 2,
        paddingVertical: 1,
      }}
    >
      <AppText variant="caption" tone="muted" style={{ fontSize: 10 }}>
        {role}
      </AppText>
    </View>
  );
}

export interface SpaceCardProps {
  space: Space;
  members?: SpaceMember[];
  /** Most recent saves, newest first — omitted while still loading. */
  recentSaves?: SaveResponse[];
  onPress: () => void;
}

/**
 * A Space, as a workspace you'd want to walk into — not the settings-page row
 * this replaces. One card communicates what the previous list needed a tap to
 * find out: what it's about (icon + colour, from `spaceIdentity`), who's in it
 * (avatar stack), how alive it is (last activity), and what's actually inside
 * (the two most recent saves).
 */
export function SpaceCard({ space, members, recentSaves, onPress }: SpaceCardProps) {
  const { palette, radius, spacing, layout } = useTheme();
  const identity = spaceIdentity(space);

  const counts = [
    `${space.saveCount} ${space.saveCount === 1 ? 'save' : 'saves'}`,
    `${space.memberCount} ${space.memberCount === 1 ? 'member' : 'members'}`,
  ].join(' · ');

  return (
    <Card radius={radius.lg} padding={0}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${space.name}, ${counts}`}
        onPress={onPress}
        haptic="selection"
        style={{ padding: layout.cardPadding }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.md }}>
          <View
            style={{
              width: 44,
              height: 44,
              borderRadius: radius.md,
              backgroundColor: `${identity.color}26`,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name={identity.glyph} size={20} weight={2} color={identity.color} />
          </View>

          <View style={{ flex: 1 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
              <AppText variant="cardTitle" numberOfLines={1} style={{ flexShrink: 1 }}>
                {space.name}
              </AppText>
              <RoleBadge role={space.myRole} />
            </View>
            <AppText variant="caption" tone="muted" style={{ marginTop: 1 }}>
              {counts}
            </AppText>
          </View>

          {/* Same shape as the back chevron, mirrored — see GroupDetailScreen's disclosure arrow. */}
          <View style={{ transform: [{ scaleX: -1 }] }}>
            <Glyph name="chevron" size={14} color={palette.textFaint} />
          </View>
        </View>

        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginTop: spacing.md,
          }}
        >
          {members && members.length > 0 ? (
            <AvatarStack members={members} size={22} max={4} />
          ) : (
            <View />
          )}
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}>
            <Glyph name="clock" size={11} color={palette.textFaint} />
            <AppText variant="caption" tone="faint" style={{ fontSize: 11 }}>
              {relativeTime(space.lastActivityAt)}
            </AppText>
          </View>
        </View>

        {recentSaves && recentSaves.length > 0 ? (
          <View
            style={{
              flexDirection: 'row',
              flexWrap: 'wrap',
              gap: spacing.xs,
              marginTop: spacing.smd,
              paddingTop: spacing.smd,
              borderTopWidth: 1,
              borderTopColor: palette.border,
            }}
          >
            {recentSaves.slice(0, 3).map((save) => (
              <View
                key={save.id}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: spacing.xxs + 2,
                  maxWidth: '100%',
                  paddingVertical: 3,
                  paddingHorizontal: spacing.sm,
                  borderRadius: radius.pill,
                  backgroundColor: palette.surfaceVariant,
                }}
              >
                <Glyph name="link" size={10} color={palette.textFaint} />
                <AppText variant="caption" tone="muted" numberOfLines={1} style={{ fontSize: 11, maxWidth: 140 }}>
                  {saveTitle(save)}
                </AppText>
              </View>
            ))}
          </View>
        ) : null}
      </Touchable>
    </Card>
  );
}
