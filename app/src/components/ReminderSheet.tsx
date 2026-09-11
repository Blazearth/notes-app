import React, { useEffect, useState } from 'react';
import { Modal, Pressable, ScrollView, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import type { SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Touchable } from '@/components/Touchable';
import { useHaptic } from '@/motion/haptics';
import {
  cancelSaveReminder,
  getScheduledReminderForSave,
  scheduleSaveReminder,
} from '@/notifications/notifications';
import { saveTitle } from '@/saves/format';
import { useTheme } from '@/theme/ThemeProvider';

interface ReminderSheetProps {
  save: SaveResponse;
  onClose: () => void;
}

interface Preset {
  id: string;
  label: string;
  sublabel: string;
  getDate: () => Date;
  icon: 'clock' | 'activity' | 'compass';
}

export function ReminderSheet({ save, onClose }: ReminderSheetProps) {
  const { palette, radius, spacing, elevation } = useTheme();
  const insets = useSafeAreaInsets();
  const fireHaptic = useHaptic();
  const [existingReminder, setExistingReminder] = useState<Date | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let mounted = true;
    getScheduledReminderForSave(save.id).then((res) => {
      if (mounted && res) {
        setExistingReminder(res.triggerDate);
      }
    });
    return () => {
      mounted = false;
    };
  }, [save.id]);

  const presets: Preset[] = [
    {
      id: '1h',
      label: 'In 1 hour',
      sublabel: new Date(Date.now() + 60 * 60 * 1000).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
      }),
      getDate: () => new Date(Date.now() + 60 * 60 * 1000),
      icon: 'clock',
    },
    {
      id: 'evening',
      label: 'This evening',
      sublabel: '7:00 PM',
      getDate: () => {
        const d = new Date();
        if (d.getHours() >= 19) {
          d.setDate(d.getDate() + 1);
        }
        d.setHours(19, 0, 0, 0);
        return d;
      },
      icon: 'clock',
    },
    {
      id: 'tomorrow',
      label: 'Tomorrow morning',
      sublabel: '9:00 AM',
      getDate: () => {
        const d = new Date();
        d.setDate(d.getDate() + 1);
        d.setHours(9, 0, 0, 0);
        return d;
      },
      icon: 'clock',
    },
    {
      id: 'weekend',
      label: 'This weekend',
      sublabel: 'Saturday 10:00 AM',
      getDate: () => {
        const d = new Date();
        const day = d.getDay();
        const diff = (6 - day + 7) % 7 || 7;
        d.setDate(d.getDate() + diff);
        d.setHours(10, 0, 0, 0);
        return d;
      },
      icon: 'compass',
    },
  ];

  const handleSelectPreset = async (preset: Preset) => {
    if (busy) return;
    setBusy(true);
    const date = preset.getDate();
    const title = saveTitle(save);
    const body =
      (save.structuredData?.summary as string | undefined) ??
      (save.structuredData?.lede as string | undefined) ??
      save.rawCaption ??
      'Tap to review this saved item in Weavr';

    const id = await scheduleSaveReminder({
      saveId: save.id,
      title,
      body,
      date,
    });

    setBusy(false);
    if (id) {
      fireHaptic('success');
      onClose();
    }
  };

  const handleCancelReminder = async () => {
    if (busy) return;
    setBusy(true);
    await cancelSaveReminder(save.id);
    setExistingReminder(null);
    setBusy(false);
    fireHaptic('medium');
    onClose();
  };

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
            paddingBottom: Math.max(insets.bottom, spacing.lg) + spacing.md,
            maxHeight: '80%',
          },
          elevation.sheet,
        ]}
      >
        {/* Header */}
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: spacing.xs,
          }}
        >
          <AppText variant="cardTitle">Set Reminder</AppText>
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Close"
            onPress={onClose}
            haptic="light"
            style={{
              width: 32,
              height: 32,
              borderRadius: 16,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: palette.surfaceVariant,
            }}
          >
            <Glyph name="close" size={16} color={palette.textMuted} />
          </Touchable>
        </View>

        <AppText variant="bodySmall" tone="muted" style={{ marginBottom: spacing.md }}>
          Get notified to revisit this save
        </AppText>

        {existingReminder ? (
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: spacing.md,
              borderRadius: radius.md,
              backgroundColor: palette.surfaceVariant,
              marginBottom: spacing.md,
            }}
          >
            <View style={{ flex: 1 }}>
              <AppText variant="caption" tone="accent" style={{ fontWeight: '600', marginBottom: 2 }}>
                ACTIVE REMINDER
              </AppText>
              <AppText variant="bodySmall">
                {existingReminder.toLocaleDateString([], {
                  weekday: 'short',
                  month: 'short',
                  day: 'numeric',
                })}{' '}
                at{' '}
                {existingReminder.toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </AppText>
            </View>
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Remove reminder"
              onPress={handleCancelReminder}
              haptic="medium"
              style={{
                paddingVertical: spacing.xs,
                paddingHorizontal: spacing.smd,
                borderRadius: radius.pill,
                borderWidth: 1,
                borderColor: palette.danger,
              }}
            >
              <AppText variant="caption" style={{ color: palette.danger, fontWeight: '600' }}>
                Cancel
              </AppText>
            </Touchable>
          </View>
        ) : null}

        <ScrollView showsVerticalScrollIndicator={false} style={{ marginBottom: spacing.sm }}>
          <View style={{ gap: spacing.sm }}>
            {presets.map((preset) => (
              <Touchable
                key={preset.id}
                accessibilityRole="button"
                accessibilityLabel={`Remind ${preset.label}`}
                onPress={() => void handleSelectPreset(preset)}
                haptic="medium"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  padding: spacing.md,
                  borderRadius: radius.md,
                  backgroundColor: palette.surfaceVariant,
                  borderWidth: 1,
                  borderColor: palette.border,
                  gap: spacing.md,
                }}
              >
                <View
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 18,
                    backgroundColor: `${palette.accent}14`,
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <Glyph name={preset.icon} size={18} color={palette.accent} />
                </View>
                <View style={{ flex: 1 }}>
                  <AppText variant="label">{preset.label}</AppText>
                  <AppText variant="caption" tone="muted">
                    {preset.sublabel}
                  </AppText>
                </View>
                <Glyph name="chevron" size={16} color={palette.textFaint} />
              </Touchable>
            ))}
          </View>
        </ScrollView>
      </View>
    </Modal>
  );
}
