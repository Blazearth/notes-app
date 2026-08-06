import React from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';

import { personColor } from '@/spaces/spaceMeta';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

function initialsOf(name?: string): string {
  const trimmed = name?.trim();
  if (!trimmed) return '?';
  const parts = trimmed.split(/\s+/);
  const first = parts[0]?.[0] ?? '';
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : '';
  return (first + last).toUpperCase() || '?';
}

export interface AvatarProps {
  /** Stable identity the colour is derived from — `userId`, not `displayName`, so it survives a rename. */
  id: string;
  name?: string;
  size?: number;
  style?: StyleProp<ViewStyle>;
}

/**
 * An initials circle, coloured by a hash of `id`.
 *
 * There is no photo anywhere in the pipeline — `SpaceMember` carries a display
 * name and nothing else — so this is the only avatar the app can render, not a
 * placeholder standing in for a future image. The ring border matches the
 * surface it is expected to sit on (a `Card`'s default background) so
 * overlapping avatars read as separated circles rather than a fused blob.
 */
export function Avatar({ id, name, size = 28, style }: AvatarProps) {
  const { palette } = useTheme();
  const color = personColor(id);

  return (
    <View
      style={[
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: color,
          alignItems: 'center',
          justifyContent: 'center',
          borderWidth: 1.5,
          borderColor: palette.surface,
        },
        style,
      ]}
    >
      <AppText style={{ fontSize: size * 0.4, fontWeight: '700', color: '#FFFFFF' }}>
        {initialsOf(name)}
      </AppText>
    </View>
  );
}

export interface AvatarStackProps {
  members: { userId: string; displayName?: string }[];
  size?: number;
  max?: number;
  style?: StyleProp<ViewStyle>;
}

/** Overlapping avatars with a "+N" overflow badge — the "Spaces feel alive" cue from a member count alone. */
export function AvatarStack({ members, size = 24, max = 3, style }: AvatarStackProps) {
  const { palette, radius } = useTheme();
  const shown = members.slice(0, max);
  const overflow = members.length - shown.length;
  const overlap = -Math.round(size * 0.32);

  return (
    <View style={[{ flexDirection: 'row', alignItems: 'center' }, style]}>
      {shown.map((member, index) => (
        <Avatar
          key={member.userId}
          id={member.userId}
          name={member.displayName}
          size={size}
          style={index === 0 ? undefined : { marginLeft: overlap }}
        />
      ))}
      {overflow > 0 ? (
        <View
          style={{
            width: size,
            height: size,
            borderRadius: radius.pill,
            backgroundColor: palette.surfaceVariant,
            borderWidth: 1.5,
            borderColor: palette.surface,
            alignItems: 'center',
            justifyContent: 'center',
            marginLeft: overlap,
          }}
        >
          <AppText style={{ fontSize: size * 0.36, fontWeight: '700' }} tone="muted">
            +{overflow}
          </AppText>
        </View>
      ) : null}
    </View>
  );
}
