import { useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { ActivityIndicator, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import { AppText } from '@/components/AppText';
import { Glyph } from '@/components/Glyph';
import { Sheet } from '@/components/Sheet';
import { Touchable } from '@/components/Touchable';
import { SPACE_TEMPLATES } from '@/spaces/spaceMeta';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Create Space, as its own sheet rather than a form permanently open on the
 * list screen — see `SpacesScreen`. Templates are here to lower the "blank
 * text field" friction the redesign feedback called out, not to add a real
 * taxonomy: `type` still free-text, and picking one only pre-fills a name
 * placeholder and picks the icon/colour `spaceIdentity` will render everywhere
 * else this Space appears.
 */
export function CreateSpaceSheet() {
  const { palette, radius, spacing } = useTheme();
  const router = useRouter();

  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const template = SPACE_TEMPLATES.find((t) => t.type === selected);

  const onCreate = useCallback(
    async (dismiss: () => void) => {
      const trimmed = name.trim() || template?.placeholder.trim() || '';
      if (!trimmed || busy) return;
      setBusy(true);
      setError(null);
      try {
        const space = await repo.createSpace(trimmed, selected ?? undefined);
        dismiss();
        // Wait for the sheet dismiss animation to complete before navigating,
        // otherwise the simultaneous animation + navigation freezes the screen.
        setTimeout(() => {
          router.push({ pathname: '/space/[id]', params: { id: space.id } });
        }, 350);
      } catch (e) {
        setError(e instanceof ApiError ? e.message : 'Could not create that Space.');
        setBusy(false);
      }
    },
    [name, template, busy, selected, router],
  );

  const canCreate = !!(name.trim() || template) && !busy;

  return (
    <Sheet title="Create a Space" subtitle="A shared collection — anything saved into it is visible to everybody in it.">
      {({ dismiss }) => (
        <View>
          <AppText variant="sectionLabel" style={{ marginBottom: spacing.smd }}>
            Start from a template
          </AppText>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginBottom: spacing.xl }}>
            {SPACE_TEMPLATES.map((t) => {
              const active = selected === t.type;
              return (
                <Touchable
                  key={t.type}
                  accessibilityRole="button"
                  accessibilityLabel={t.label}
                  accessibilityState={{ selected: active }}
                  onPress={() => setSelected(active ? null : t.type)}
                  haptic="selection"
                  weight="control"
                  style={{
                    width: '31%',
                    alignItems: 'center',
                    gap: spacing.xs,
                    paddingVertical: spacing.md,
                    borderRadius: radius.md,
                    borderWidth: 1,
                    borderColor: active ? t.color : palette.border,
                    backgroundColor: active ? `${t.color}1F` : palette.surface,
                  }}
                >
                  <Glyph name={t.glyph} size={18} weight={2} color={active ? t.color : palette.textMuted} />
                  <AppText variant="caption" style={{ fontSize: 11 }} tone={active ? 'default' : 'muted'}>
                    {t.label}
                  </AppText>
                </Touchable>
              );
            })}
          </View>

          <AppText variant="sectionLabel" style={{ marginBottom: spacing.smd }}>
            Name
          </AppText>
          <TextInput
            value={name}
            onChangeText={setName}
            placeholder={template?.placeholder ?? 'Copenhagen trip'}
            placeholderTextColor={palette.textFaint}
            style={{
              color: palette.text,
              backgroundColor: palette.surface,
              borderWidth: 1,
              borderColor: palette.border,
              borderRadius: radius.sm,
              paddingHorizontal: spacing.md,
              paddingVertical: spacing.smd,
              fontSize: 14,
              marginBottom: spacing.lg,
            }}
            returnKeyType="done"
            onSubmitEditing={() => void onCreate(dismiss)}
            editable={!busy}
          />

          {error ? (
            <AppText variant="caption" style={{ color: palette.danger, marginBottom: spacing.md }}>
              {error}
            </AppText>
          ) : null}

          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Create Space"
            accessibilityState={{ disabled: !canCreate }}
            disabled={!canCreate}
            onPress={() => void onCreate(dismiss)}
            haptic="medium"
            baseOpacity={canCreate ? 1 : 0.5}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'center',
              gap: spacing.sm,
              paddingVertical: spacing.md,
              borderRadius: radius.sm,
              backgroundColor: palette.accent,
            }}
          >
            {busy ? (
              <ActivityIndicator color={palette.onAccent} size="small" />
            ) : (
              <>
                <Glyph name="plus" size={16} weight={2.5} color={palette.onAccent} />
                <AppText style={{ color: palette.onAccent, fontWeight: '600' }}>Create Space</AppText>
              </>
            )}
          </Touchable>
        </View>
      )}
    </Sheet>
  );
}
