import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ActivityIndicator, RefreshControl, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import type { Space } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * The Spaces tab, on real data.
 *
 * <p>This screen used to render a single hard-coded space from
 * `sampleContent` — a name, three fake members and a chat thread — because no
 * endpoint served one. It now lists what the user is actually in, and the
 * fiction is gone rather than left sitting beside real rows.
 */

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

function SpaceRow({ space, onPress }: { space: Space; onPress: () => void }) {
  const { palette, radius, spacing, layout } = useTheme();
  const counts = [
    `${space.saveCount} ${space.saveCount === 1 ? 'save' : 'saves'}`,
    `${space.memberCount} ${space.memberCount === 1 ? 'member' : 'members'}`,
  ].join(' · ');

  return (
    <Card radius={radius.md} padding={0}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${space.name}, ${counts}`}
        onPress={onPress}
        haptic="selection"
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.md,
          paddingVertical: spacing.md,
          paddingHorizontal: layout.rowPadding,
        }}
      >
        <View
          style={{
            width: 34,
            height: 34,
            borderRadius: radius.sm,
            backgroundColor: palette.surfaceVariant,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="layers" size={16} />
        </View>

        <View style={{ flex: 1 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
            <AppText variant="cardTitle" numberOfLines={1} style={{ flexShrink: 1 }}>
              {space.name}
            </AppText>
            <RoleBadge role={space.myRole} />
          </View>
          <AppText variant="caption" tone="muted">
            {counts}
          </AppText>
        </View>

        <Glyph name="chevron" size={14} color={palette.textFaint} />
      </Touchable>
    </Card>
  );
}

export function SpacesScreen() {
  const { palette, radius, spacing } = useTheme();
  const router = useRouter();

  const [spaces, setSpaces] = useState<Space[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setSpaces(await repo.listSpaces());
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load your Spaces.');
    }
  }, []);

  // Refetched on focus rather than once on mount: joining a Space or adding a
  // save happens on other screens, and coming back to a stale count reads as
  // the action having failed.
  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load]),
  );

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

  const onCreate = useCallback(async () => {
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const space = await repo.createSpace(trimmed);
      setName('');
      await load();
      router.push({ pathname: '/space/[id]', params: { id: space.id } });
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : 'Could not create that Space.');
    } finally {
      setBusy(false);
    }
  }, [name, busy, load, router]);

  const onJoin = useCallback(async () => {
    const trimmed = code.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const space = await repo.acceptInvite(trimmed);
      setCode('');
      await load();
      router.push({ pathname: '/space/[id]', params: { id: space.id } });
    } catch (e) {
      // 404 covers expired, revoked and used-up alike — the server does not
      // distinguish them, and neither should this.
      setNotice(e instanceof ApiError ? e.message : 'That invite link did not work.');
    } finally {
      setBusy(false);
    }
  }, [code, busy, load, router]);

  const inputStyle = {
    flex: 1,
    color: palette.text,
    backgroundColor: palette.surface,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.smd,
    fontSize: 14,
  } as const;

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}>
      <Reveal index={0} style={{ marginBottom: spacing.lg }}>
        <AppText variant="title">Spaces</AppText>
        <AppText variant="caption" tone="muted" style={{ marginTop: 2 }}>
          Shared collections. Anything you save into one is visible to everybody in it.
        </AppText>
      </Reveal>

      {notice ? (
        <Reveal index={1}>
          <Card style={{ marginBottom: spacing.md }}>
            <AppText variant="bodySmall">{notice}</AppText>
          </Card>
        </Reveal>
      ) : null}

      <Reveal index={1} style={{ marginBottom: spacing.xl }}>
        <SectionLabel>New Space</SectionLabel>
        <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'center' }}>
          <TextInput
            value={name}
            onChangeText={setName}
            placeholder="Copenhagen trip"
            placeholderTextColor={palette.textFaint}
            style={inputStyle}
            returnKeyType="done"
            onSubmitEditing={onCreate}
            editable={!busy}
          />
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Create Space"
            onPress={onCreate}
            haptic="selection"
            weight="tile"
            style={{
              width: 42,
              height: 42,
              borderRadius: radius.sm,
              backgroundColor: name.trim() ? palette.accent : palette.surfaceVariant,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph
              name="plus"
              size={16}
              weight={2.5}
              color={name.trim() ? palette.onAccent : palette.textFaint}
            />
          </Touchable>
        </View>
      </Reveal>

      <Reveal index={2} style={{ marginBottom: spacing.xl }}>
        <SectionLabel>Join with a code</SectionLabel>
        <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'center' }}>
          <TextInput
            value={code}
            onChangeText={setCode}
            placeholder="Paste an invite code"
            placeholderTextColor={palette.textFaint}
            style={inputStyle}
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="go"
            onSubmitEditing={onJoin}
            editable={!busy}
          />
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Join Space"
            onPress={onJoin}
            haptic="selection"
            weight="tile"
            style={{
              width: 42,
              height: 42,
              borderRadius: radius.sm,
              backgroundColor: code.trim() ? palette.accent : palette.surfaceVariant,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph
              name="download"
              size={16}
              color={code.trim() ? palette.onAccent : palette.textFaint}
            />
          </Touchable>
        </View>
      </Reveal>

      <SectionLabel>Your Spaces</SectionLabel>

      {spaces === null && !error ? (
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {error ? (
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Could not load Spaces
          </AppText>
          <AppText variant="caption" tone="muted">
            {error}
          </AppText>
        </Card>
      ) : null}

      {spaces && spaces.length === 0 && !error ? (
        <Card>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            No Spaces yet
          </AppText>
          <AppText variant="caption" tone="muted">
            Create one above for a trip, a project or a household — then invite people with a
            link.
          </AppText>
        </Card>
      ) : null}

      <View style={{ gap: spacing.sm }}>
        {spaces?.map((space, index) => (
          <Reveal key={space.id} index={index}>
            <SpaceRow space={space} onPress={() => router.push({ pathname: '/space/[id]', params: { id: space.id } })} />
          </Reveal>
        ))}
      </View>

      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
