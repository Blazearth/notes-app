import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Linking, View } from 'react-native';

import { ApiError } from '@/api/client';
import { repo } from '@/data';
import type { SaveResponse } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { Discussion } from '@/components/Discussion';
import { LifecycleStrip } from '@/components/LifecycleStrip';
import { buildDetailModel, type DetailField } from '@/saves/detailModel';
import { STATUS_LABELS, saveTitle } from '@/saves/format';
import { useSaves } from '@/saves/SavesProvider';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';
import { AddToSpaceSheet } from './AddToSpaceSheet';

function BackButton() {
  const { palette, radius, icon, spacing } = useTheme();
  const router = useRouter();
  return (
    <Touchable
      accessibilityRole="button"
      accessibilityLabel="Back"
      weight="tile"
      onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))}
      style={{
        width: 36,
        height: 36,
        borderRadius: radius.sm,
        backgroundColor: palette.surface,
        borderWidth: 1,
        borderColor: palette.border,
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: spacing.lg,
      }}
    >
      <Glyph name="chevron" size={icon.sm} />
    </Touchable>
  );
}

function Chips({ items }: { items: string[] }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs }}>
      {items.map((item, i) => (
        <View
          key={`${item}-${i}`}
          style={{
            paddingVertical: 5,
            paddingHorizontal: spacing.smd,
            borderRadius: radius.pill,
            backgroundColor: palette.surfaceVariant,
            borderWidth: 1,
            borderColor: palette.border,
          }}
        >
          <AppText variant="bodySmall">{item}</AppText>
        </View>
      ))}
    </View>
  );
}

function Steps({ items }: { items: string[] }) {
  const { palette, spacing } = useTheme();
  return (
    <View style={{ gap: spacing.smd }}>
      {items.map((item, i) => (
        <View key={`${i}-${item}`} style={{ flexDirection: 'row', gap: spacing.smd }}>
          <View
            style={{
              width: 22,
              height: 22,
              borderRadius: 11,
              backgroundColor: palette.surfaceVariant,
              borderWidth: 1,
              borderColor: palette.border,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
              {i + 1}
            </AppText>
          </View>
          <AppText style={{ flex: 1 }}>{item}</AppText>
        </View>
      ))}
    </View>
  );
}

/**
 * One entry of a nested object array — an exercise card, a structured item.
 * The same surface treatment as a chip, scaled up to hold a title, a compact
 * meta line, and labelled rows.
 */
function ObjectCards({ objects }: { objects: NonNullable<DetailField['objects']> }) {
  const { palette, radius, spacing } = useTheme();
  return (
    <View style={{ gap: spacing.smd }}>
      {objects.map((object, i) => (
        <View
          key={`${object.title ?? 'item'}-${i}`}
          style={{
            padding: spacing.md,
            borderRadius: radius.md,
            backgroundColor: palette.surfaceVariant,
            borderWidth: 1,
            borderColor: palette.border,
            gap: spacing.xs,
          }}
        >
          {object.title ? <AppText variant="cardTitle">{object.title}</AppText> : null}
          {object.meta ? (
            <AppText variant="bodySmall" tone="muted">
              {object.meta}
            </AppText>
          ) : null}
          {object.rows.map((row) => (
            <View key={row.label} style={{ gap: 4 }}>
              <AppText variant="caption" tone="muted">
                {row.label}
              </AppText>
              {row.items ? <Chips items={row.items} /> : <AppText variant="bodySmall">{row.text}</AppText>}
            </View>
          ))}
        </View>
      ))}
    </View>
  );
}

function Field({ field }: { field: DetailField }) {
  const { spacing } = useTheme();
  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel>{field.label}</SectionLabel>
      {field.style === 'objects' && field.objects ? (
        <ObjectCards objects={field.objects} />
      ) : field.style === 'steps' && field.items ? (
        <Steps items={field.items} />
      ) : field.items ? (
        <Chips items={field.items} />
      ) : (
        <AppText>{field.text}</AppText>
      )}
    </View>
  );
}

/**
 * The status page for a save the pipeline has not finished with.
 *
 * A processing or failed save is a legitimate thing to open — it is in the feed
 * and it is tappable — so it has to explain itself rather than render as an
 * empty detail view. `pending` is called out separately because it is not a
 * failure: the daily model budget was spent and the save is queued for the next
 * window, which the user should read as "waiting", not "broken".
 */
function UnfinishedSave({ save }: { save: SaveResponse }) {
  const { palette, spacing } = useTheme();

  const copy: Record<string, { title: string; body: string }> = {
    processing: {
      title: 'Still working on this one',
      body: 'Weavr is reading the source and pulling out the details. Pull down on the feed to check again.',
    },
    pending: {
      title: 'Queued for tomorrow',
      body: "Today's AI budget is spent, so this save is waiting for the next window. Nothing is lost — it will be processed automatically.",
    },
    failed: {
      title: "Couldn't process this",
      body: save.errorMessage ?? 'Something went wrong and Weavr could not extract anything useful.',
    },
  };

  const { title, body } = copy[save.status] ?? copy.processing;

  return (
    <Card>
      <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
        {title}
      </AppText>
      <AppText variant="bodySmall" tone="muted">
        {body}
      </AppText>
      {save.status === 'failed' && save.errorCode ? (
        <AppText
          variant="caption"
          tone="muted"
          style={{ marginTop: spacing.md, color: palette.textFaint }}
        >
          {save.errorCode}
        </AppText>
      ) : null}
    </Card>
  );
}

/**
 * The Act, on the one type that has one.
 *
 * <p>The conversion is a queued job that spends a Gemini request, so the list
 * is not updated by the time the call returns — hence "Adding…" then "Added",
 * rather than navigating straight to a list that would still be empty. The
 * follow-up link is offered instead of forced: adding a second recipe before
 * going shopping is the common case, and this is the screen you would do it
 * from.
 */
function AddToShoppingList({ saveId }: { saveId: string }) {
  const { palette, radius, spacing, icon } = useTheme();
  const router = useRouter();
  const [state, setState] = useState<'idle' | 'adding' | 'added' | 'error'>('idle');
  const [message, setMessage] = useState<string | null>(null);

  const add = async () => {
    setState('adding');
    setMessage(null);
    try {
      await repo.convertToShoppingList(saveId);
      setState('added');
    } catch (e) {
      setState('error');
      setMessage(e instanceof ApiError ? e.message : 'Could not add this recipe.');
    }
  };

  if (state === 'added') {
    return (
      <View style={{ marginBottom: spacing.xl, gap: spacing.sm }}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.smd,
            padding: spacing.md,
            borderRadius: radius.md,
            backgroundColor: palette.accentContainer,
          }}
        >
          <AppText tone="onAccentContainer" style={{ flex: 1 }}>
            Adding to your shopping list…
          </AppText>
          <Touchable
            accessibilityRole="button"
            onPress={() => router.push('/shopping-list')}
            haptic="medium"
          >
            <AppText variant="label" tone="onAccentContainer">
              View list
            </AppText>
          </Touchable>
        </View>
        {/* Honest about the delay: the ingredients are normalised by a model
            call, so the list is a few seconds behind this tap. */}
        <AppText variant="caption" tone="muted">
          Ingredients are being sorted into aisles — pull to refresh the list in a moment.
        </AppText>
      </View>
    );
  }

  return (
    <View style={{ marginBottom: spacing.xl }}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel="Add this recipe to your shopping list"
        onPress={() => void add()}
        disabled={state === 'adding'}
        haptic="medium"
        weight="tile"
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'center',
          gap: spacing.smd,
          paddingVertical: spacing.md,
          borderRadius: radius.md,
          backgroundColor: palette.accent,
          opacity: state === 'adding' ? 0.6 : 1,
        }}
      >
        {state === 'adding' ? (
          <ActivityIndicator color={palette.onAccent} size="small" />
        ) : (
          <Glyph name="plus" size={icon.sm} weight={2} color={palette.onAccent} />
        )}
        <AppText variant="label" style={{ color: palette.onAccent }}>
          {state === 'adding' ? 'Adding…' : 'Add to shopping list'}
        </AppText>
      </Touchable>
      {state === 'error' && message ? (
        <AppText variant="caption" style={{ marginTop: spacing.sm, color: palette.danger }}>
          {message}
        </AppText>
      ) : null}
    </View>
  );
}

export function SaveDetailScreen({ id }: { id: string }) {
  const { palette, radius, spacing, icon } = useTheme();
  const { saves } = useSaves();

  // Start from the feed's copy when it has one, so opening a card from Home is
  // instant and the fetch below only fills in anything that changed. Arriving
  // from a search result or a cold link has no cached copy and shows a spinner.
  const cached = saves.find((s) => s.id === id) ?? null;
  const [save, setSave] = useState<SaveResponse | null>(cached);
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(!cached);
  const [showSpaceSheet, setShowSpaceSheet] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setSave(await repo.getSave(id));
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError('server', 'Something went wrong', null));
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const model = save ? buildDetailModel(save) : null;

  return (
    <Screen>
      <Reveal index={0}>
        <BackButton />
      </Reveal>

      {loading && !save ? (
        <View style={{ paddingVertical: spacing.xxl * 2, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {error && !save ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              {error.kind === 'notFound' ? 'Save not found' : "Couldn't load this save"}
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              {error.message}
            </AppText>
            {error.kind !== 'notFound' ? (
              <Touchable accessibilityRole="button" onPress={() => void load()} haptic="medium">
                <AppText variant="label" tone="accent">
                  Try again
                </AppText>
              </Touchable>
            ) : null}
          </Card>
        </Reveal>
      ) : null}

      {save ? (
        <>
          <Reveal index={1}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
              {save.knowledgeType ? (
                <View
                  style={{
                    width: 8,
                    height: 8,
                    borderRadius: 4,
                    backgroundColor: TYPE_COLORS[save.knowledgeType] ?? TYPE_COLORS.other,
                  }}
                />
              ) : null}
              <AppText variant="sectionLabel" tone="muted">
                {save.knowledgeType ?? STATUS_LABELS[save.status]}
              </AppText>
            </View>
            <AppText variant="display" style={{ marginTop: spacing.xs, marginBottom: spacing.xs }}>
              {model?.title ?? saveTitle(save)}
            </AppText>
            {model?.meta ? (
              <AppText tone="muted" style={{ marginBottom: spacing.lg }}>
                {model.meta}
              </AppText>
            ) : (
              <View style={{ marginBottom: spacing.lg }} />
            )}
          </Reveal>

          {model?.lede ? (
            <Reveal index={2}>
              <AppText style={{ marginBottom: spacing.xl, lineHeight: 22 }}>{model.lede}</AppText>
            </Reveal>
          ) : null}

          {/* The only Act that exists, and only recipes have it. Placed above
              the fields because it is the reason to open a recipe at all. */}
          {model && save.knowledgeType === 'recipe' ? (
            <Reveal index={2}>
              <AddToShoppingList saveId={save.id} />
            </Reveal>
          ) : null}

          {/* Add to Space */}
          {save.status === 'ready' ? (
            <Reveal index={2}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={save.spaceId ? 'Move to a different Space' : 'Add to a Space'}
                onPress={() => setShowSpaceSheet(true)}
                haptic="light"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: spacing.sm,
                  paddingVertical: spacing.md,
                  borderRadius: radius.sm,
                  borderWidth: 1,
                  borderColor: palette.border,
                  backgroundColor: palette.surface,
                  marginBottom: spacing.smd,
                }}
              >
                <Glyph name="layers" size={16} weight={2} color={palette.textMuted} />
                <AppText variant="label" tone="muted">
                  {save.spaceId ? 'Move Space' : 'Add to Space'}
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {model ? (
            model.fields.map((field, i) => (
              <Reveal key={field.label} index={3 + i}>
                <Field field={field} />
              </Reveal>
            ))
          ) : (
            <Reveal index={2}>
              <UnfinishedSave save={save} />
            </Reveal>
          )}

          {/* Progress only makes sense once there is something to make
              progress on — a save still being processed has no content yet. */}
          {save.status === 'ready' ? (
            <Reveal index={3 + (model?.fields.length ?? 1)}>
              <LifecycleStrip
                saveId={save.id}
                value={save.lifecycleStatus ?? 'saved'}
                onChange={() => void load()}
              />
            </Reveal>
          ) : null}

          {/* Renders nothing for a private save: a comment thread only you can
              see is a note to self, not a discussion. */}
          <Discussion saveId={save.id} spaceId={save.spaceId} />

          {save.sourceUrl ? (
            <Reveal index={4 + (model?.fields.length ?? 1)}>
              <SectionLabel>Source</SectionLabel>
              <Touchable
                accessibilityRole="link"
                accessibilityLabel={`Open ${save.sourceUrl}`}
                haptic="medium"
                onPress={() => {
                  // Nothing to do if the OS has no handler — better a no-op
                  // than an unhandled rejection on a malformed stored URL.
                  void Linking.openURL(save.sourceUrl as string).catch(() => {});
                }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: spacing.smd,
                  backgroundColor: palette.surface,
                  borderWidth: 1,
                  borderColor: palette.border,
                  borderRadius: radius.md,
                  padding: spacing.md,
                }}
              >
                <Glyph name="link" size={icon.sm} />
                <AppText variant="bodySmall" style={{ flex: 1 }} numberOfLines={1}>
                  {save.sourceUrl}
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}
        </>
      ) : null}
    </Screen>
    {save && showSpaceSheet ? (
      <AddToSpaceSheet save={save} onClose={() => setShowSpaceSheet(false)} />
    ) : null}
  );
}
