import React, { useState } from 'react';
import { View } from 'react-native';

import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { HatchThumb } from '@/components/HatchThumb';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { Touchable } from '@/components/Touchable';
import { SectionLabel } from '@/components/SectionLabel';
import { Segmented } from '@/components/Segmented';
import { SPACE_DETAIL, type TaskItem } from '@/data/sampleContent';
import { useTheme } from '@/theme/ThemeProvider';

type TabValue = (typeof SPACE_DETAIL.tabs)[number]['value'];

function MemberStack() {
  const { palette, spacing } = useTheme();
  const ring = {
    width: 28,
    height: 28,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: palette.surface,
    marginLeft: -spacing.sm,
  } as const;

  return (
    <View style={{ flexDirection: 'row' }}>
      <View style={[ring, { backgroundColor: palette.surfaceVariant }]} />
      <View style={[ring, { backgroundColor: palette.border }]} />
      <View
        style={[
          ring,
          {
            backgroundColor: palette.hatchB,
            alignItems: 'center',
            justifyContent: 'center',
          },
        ]}
      >
        <AppText variant="caption" tone="muted" style={{ fontSize: 10, fontWeight: '600' }}>
          +{SPACE_DETAIL.extraMembers}
        </AppText>
      </View>
    </View>
  );
}

function TaskRow({ task }: { task: TaskItem }) {
  const { palette, radius, spacing, layout } = useTheme();
  return (
    <Card radius={radius.md} padding={0}>
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.smd,
          paddingVertical: spacing.smd,
          paddingHorizontal: layout.rowPadding,
        }}
      >
        <View
          style={{
            width: 18,
            height: 18,
            borderRadius: radius.xs,
            backgroundColor: task.done ? palette.accent : 'transparent',
            borderWidth: task.done ? 0 : 2,
            borderColor: palette.textFaint,
          }}
        />
        <AppText
          variant="bodySmall"
          tone={task.done ? 'muted' : 'default'}
          style={task.done ? { textDecorationLine: 'line-through' } : undefined}
        >
          {task.title}
        </AppText>
      </View>
    </Card>
  );
}

export function SpacesScreen() {
  const { palette, radius, spacing } = useTheme();
  const [tab, setTab] = useState<TabValue>('saves');

  return (
    <Screen>
      {/* Breadcrumb back to the space list */}
      <Reveal index={0}>
        <Touchable
          accessibilityRole="button"
          // There is no space list to go back to yet.
          haptic={null}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            alignSelf: 'flex-start',
            gap: spacing.xs + 2,
            marginBottom: spacing.md + 2,
          }}
        >
          <Glyph name="layers" size={14} weight={2} />
          <AppText tone="muted" style={{ fontSize: 12.5 }}>
            Spaces
          </AppText>
        </Touchable>
      </Reveal>

      <Reveal
        index={1}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.lg,
        }}
      >
        <AppText variant="title">{SPACE_DETAIL.name}</AppText>
        <MemberStack />
      </Reveal>

      <Reveal index={2} style={{ marginBottom: spacing.xl }}>
        <Segmented
          options={SPACE_DETAIL.tabs.map((t) => ({ value: t.value, label: t.label }))}
          value={tab}
          onChange={setTab}
        />
      </Reveal>

      {/*
        The mockup renders Saves, Tasks and Chat stacked under the Saves tab —
        it is the space overview. The other tabs narrow to one section each so
        the control does something rather than being decoration.
      */}
      {/*
        Each block is keyed by `tab`, so a tab change remounts it and the
        content animates in behind the sliding thumb. Two of these sections
        appear under more than one tab; without the key React would reuse them
        and the switch would land with the header moving and the body static.
      */}
      {tab === 'saves' && (
        <Reveal key={`saves-${tab}`}>
          <SectionLabel>Shared saves</SectionLabel>
          <View
            style={{
              flexDirection: 'row',
              gap: spacing.smd,
              marginBottom: spacing.xxl - 2,
            }}
          >
            {SPACE_DETAIL.sharedSaves.map((save) => (
              <Card key={save.id} padding={0} radius={radius.md} style={{ flex: 1, overflow: 'hidden' }}>
                <HatchThumb label={save.thumbLabel} height={80} radius={0} />
                <View style={{ padding: spacing.smd }}>
                  <AppText variant="cardTitle" style={{ fontSize: 12 }} numberOfLines={1}>
                    {save.title}
                  </AppText>
                  <AppText variant="caption" tone="muted" style={{ fontSize: 10.5 }}>
                    {save.source}
                  </AppText>
                </View>
              </Card>
            ))}
          </View>
        </Reveal>
      )}

      {tab === 'calendar' && (
        <Card style={{ marginBottom: spacing.xxl - 2 }}>
          <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
            Nothing scheduled
          </AppText>
          <AppText variant="caption" tone="muted">
            Dated saves in this space — reservations, screenings, flights — will land here.
          </AppText>
        </Card>
      )}

      {(tab === 'saves' || tab === 'tasks') && (
        <Reveal key={`tasks-${tab}`} index={1}>
          <SectionLabel>Tasks</SectionLabel>
          <View style={{ gap: spacing.sm, marginBottom: spacing.xxl - 2 }}>
            {SPACE_DETAIL.tasks.map((task) => (
              <TaskRow key={task.id} task={task} />
            ))}
          </View>
        </Reveal>
      )}

      {(tab === 'saves' || tab === 'chat') && (
        <Reveal key={`chat-${tab}`} index={2}>
          <SectionLabel>Chat</SectionLabel>
          <View style={{ gap: spacing.sm }}>
            {SPACE_DETAIL.chat.map((message) => (
              <View
                key={message.id}
                style={{
                  alignSelf: 'flex-start',
                  maxWidth: '80%',
                  backgroundColor: palette.surface,
                  borderWidth: 1,
                  borderColor: palette.border,
                  borderRadius: radius.md,
                  borderBottomLeftRadius: radius.xs - 1,
                  paddingVertical: spacing.smd,
                  paddingHorizontal: spacing.md,
                }}
              >
                <AppText variant="label" tone="accent" style={{ fontSize: 11, marginBottom: 2 }}>
                  {message.author}
                </AppText>
                <AppText variant="bodySmall">{message.body}</AppText>
              </View>
            ))}
          </View>
        </Reveal>
      )}

      <View style={{ height: spacing.lg }} />
    </Screen>
  );
}
