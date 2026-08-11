import { useRouter } from 'expo-router';
import { useKeepAwake } from 'expo-keep-awake';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Image, Linking, ScrollView, StyleSheet, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import type { SaveResponse } from '@/api/types';
import { useLive, useLiveValue } from '@/local';
import { sync } from '@/local/sync';
import {
  writeConvertToShoppingList,
  writeEntityState,
  writeNote,
  writeSaveItemState,
} from '@/local/writes';
import { repo } from '@/data';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { RestTimer } from '@/components/RestTimer';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { SaveThumb } from '@/components/SaveThumb';
import { Touchable } from '@/components/Touchable';
import { Discussion } from '@/components/Discussion';
import { LifecycleStrip } from '@/components/LifecycleStrip';
import { buildCardModel } from '@/saves/cardModel';
import { buildDetailModel, type DetailField, type DetailObject, type EntityStates } from '@/saves/detailModel';
import { STATUS_LABELS, saveTitle } from '@/saves/format';
import { baseServings, scaleQuantity } from '@/saves/scaling';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
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

type ItemStates = Record<string, Record<string, unknown>> | undefined;
type SetItemState = (itemPath: string, state: Record<string, unknown>) => void;
type SetEntityState = (entityKey: string, state: Record<string, unknown>) => void;

/** done/total across a checklist or a course's sections — Phase 4 §4.2. */
function ProgressBar({ done, total }: { done: number; total: number }) {
  const { palette, spacing } = useTheme();
  const fraction = total > 0 ? done / total : 0;
  return (
    <View style={{ marginBottom: spacing.smd }}>
      <View style={{ height: 4, borderRadius: 2, backgroundColor: palette.border }}>
        <View
          style={{
            width: `${Math.round(fraction * 100)}%`,
            height: '100%',
            borderRadius: 2,
            backgroundColor: palette.accent,
          }}
        />
      </View>
      <AppText variant="caption" tone="muted" style={{ marginTop: 4 }}>
        {done} of {total} done
      </AppText>
    </View>
  );
}

/** The checklist-item / course-section / workout-exercise "mark done" tick. */
function CheckControl({ done, onToggle }: { done: boolean; onToggle: () => void }) {
  const { palette, spacing } = useTheme();
  return (
    <Touchable
      accessibilityRole="checkbox"
      accessibilityState={{ checked: done }}
      accessibilityLabel={done ? 'Mark as not done' : 'Mark done'}
      onPress={onToggle}
      haptic="light"
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginTop: spacing.xs }}
    >
      <Glyph name={done ? 'checkSquare' : 'square'} size={16} color={done ? palette.accent : palette.textMuted} />
      <AppText variant="bodySmall" tone={done ? 'accent' : 'muted'}>
        {done ? 'Done' : 'Mark done'}
      </AppText>
    </Touchable>
  );
}

/** recommendation_list's watched toggle + 1–5 star rating — the flagship Phase 4 behavior. */
function WatchControl({
  state,
  onChange,
}: {
  state: Record<string, unknown> | undefined;
  onChange: (state: Record<string, unknown>) => void;
}) {
  const { palette, spacing } = useTheme();
  const done = state?.done === true;
  const rating = typeof state?.rating === 'number' ? state.rating : 0;
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, marginTop: spacing.xs }}>
      <Touchable
        accessibilityRole="checkbox"
        accessibilityState={{ checked: done }}
        accessibilityLabel={done ? 'Mark as not watched' : 'Mark watched'}
        onPress={() => onChange({ ...state, done: !done })}
        haptic="light"
        style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}
      >
        <Glyph name={done ? 'checkSquare' : 'square'} size={16} color={done ? palette.accent : palette.textMuted} />
        <AppText variant="bodySmall" tone={done ? 'accent' : 'muted'}>
          {done ? 'Watched' : 'Mark watched'}
        </AppText>
      </Touchable>
      <View style={{ flexDirection: 'row', gap: 2 }}>
        {[1, 2, 3, 4, 5].map((n) => (
          <Touchable
            key={n}
            accessibilityRole="button"
            accessibilityLabel={`Rate ${n} of 5`}
            onPress={() => onChange({ ...state, rating: n })}
            haptic="selection"
          >
            <Glyph name="star" size={14} color={n <= rating ? palette.accent : palette.textFaint} />
          </Touchable>
        ))}
      </View>
    </View>
  );
}

/** Horizontally scrolling rail that bleeds into the screen gutter — mirrors `HomeScreen`'s `Rail`. */
function Rail({ children }: { children: React.ReactNode }) {
  const { layout, spacing } = useTheme();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={{ marginHorizontal: -layout.screenGutter }}
      contentContainerStyle={{ paddingHorizontal: layout.screenGutter, gap: spacing.md }}
    >
      {children}
    </ScrollView>
  );
}

/** One tile in the "You also saved" rail — thumbnail, title, meta, nothing else. */
function RelatedCard({ save, onPress }: { save: SaveResponse; onPress: () => void }) {
  const { radius, spacing } = useTheme();
  const typeMeta = save.knowledgeType ? saveTypeMeta(save.knowledgeType) : undefined;
  const model = buildCardModel(save);
  return (
    <Card padding={0} radius={radius.lg} style={{ width: 156, overflow: 'hidden' }}>
      <Touchable accessibilityRole="button" onPress={onPress} haptic="selection">
        <SaveThumb
          thumbnailUrl={save.thumbnailUrl}
          width={156}
          height={90}
          radius={0}
          tint={typeMeta?.color}
          glyph={typeMeta?.glyph}
        />
        <View style={{ padding: spacing.smd }}>
          <AppText variant="cardTitle" numberOfLines={1}>
            {model?.title ?? saveTitle(save)}
          </AppText>
          {model?.meta ? (
            <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
              {model.meta}
            </AppText>
          ) : null}
        </View>
      </Touchable>
    </Card>
  );
}

/**
 * "You also saved…" — Phase 5 §5.3. Fetches lazily, only once the save is
 * `ready` (an unclassified save has no embedding to compare against), and
 * renders nothing on an empty result: the server's distance cutoff means an
 * empty array is "nothing genuinely similar," the same honest-empty-state
 * rule the search screen already follows, not a loading or error state.
 */
function RelatedRail({ saveId }: { saveId: string }) {
  const router = useRouter();
  const [related, setRelated] = useState<SaveResponse[]>([]);

  useEffect(() => {
    let cancelled = false;
    repo
      .getRelatedSaves(saveId)
      .then((saves) => {
        if (!cancelled) setRelated(saves);
      })
      .catch(() => {
        // Silent: a failed suggestion rail is not worth an error card on an
        // otherwise-successful save view.
      });
    return () => {
      cancelled = true;
    };
  }, [saveId]);

  if (related.length === 0) return null;

  return (
    <View style={{ marginBottom: 24 }}>
      <SectionLabel>You also saved</SectionLabel>
      <Rail>
        {related.map((save) => (
          <RelatedCard
            key={save.id}
            save={save}
            onPress={() => router.push({ pathname: '/save/[id]', params: { id: save.id } })}
          />
        ))}
      </Rail>
    </View>
  );
}

/** An itinerary place's "Open in Maps" deep link — pure URL, no state. */
function MapsButton({ url }: { url: string }) {
  const { palette, spacing } = useTheme();
  return (
    <Touchable
      accessibilityRole="link"
      accessibilityLabel="Open in Maps"
      onPress={() => void Linking.openURL(url).catch(() => {})}
      haptic="light"
      style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs, marginTop: spacing.xs }}
    >
      <Glyph name="mapPin" size={14} color={palette.accent} />
      <AppText variant="bodySmall" tone="accent">
        Open in Maps
      </AppText>
    </Touchable>
  );
}

/**
 * One entry of a nested object array — an exercise card, a structured item.
 * The same surface treatment as a chip, scaled up to hold a title, a compact
 * meta line, and labelled rows — plus whichever Phase 4 control the object
 * carries (`docs/next-phases.md` §4.2).
 */
function ObjectCards({
  objects,
  itemStates,
  entityStates,
  onSetItemState,
  onSetEntityState,
}: {
  objects: NonNullable<DetailField['objects']>;
  itemStates: ItemStates;
  entityStates: EntityStates;
  onSetItemState: SetItemState;
  onSetEntityState: SetEntityState;
}) {
  const { palette, radius, spacing } = useTheme();
  return (
    <View style={{ gap: spacing.smd }}>
      {objects.map((object: DetailObject, i) => {
        // K2 dual-read: entity state wins over item state when an object
        // carries an entityKey (recommendation_list items only) — mirrors
        // `detailModel.resolveObjectState`.
        const state = object.entityKey
          ? (entityStates?.[object.entityKey] ?? (object.statePath ? itemStates?.[object.statePath] : undefined))
          : object.statePath
            ? itemStates?.[object.statePath]
            : undefined;
        const applyChange = (next: Record<string, unknown>) => {
          if (object.entityKey) onSetEntityState(object.entityKey, next);
          else if (object.statePath) onSetItemState(object.statePath, next);
        };
        return (
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
            {object.imageUrl ? (
              <Image
                source={{ uri: object.imageUrl }}
                style={{ width: 56, height: 84, borderRadius: radius.sm, marginBottom: spacing.xs }}
                resizeMode="cover"
              />
            ) : null}
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
            {object.control === 'check' && (object.statePath || object.entityKey) ? (
              <CheckControl
                done={state?.done === true}
                onToggle={() => applyChange({ ...state, done: state?.done !== true })}
              />
            ) : null}
            {object.control === 'watch' && (object.statePath || object.entityKey) ? (
              <WatchControl state={state} onChange={applyChange} />
            ) : null}
            {object.restLabel ? <RestTimer label={object.restLabel} /> : null}
            {object.mapsUrl ? <MapsButton url={object.mapsUrl} /> : null}
          </View>
        );
      })}
    </View>
  );
}

function Field({
  field,
  itemStates,
  entityStates,
  onSetItemState,
  onSetEntityState,
}: {
  field: DetailField;
  itemStates: ItemStates;
  entityStates: EntityStates;
  onSetItemState: SetItemState;
  onSetEntityState: SetEntityState;
}) {
  const { spacing } = useTheme();
  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel>{field.label}</SectionLabel>
      {field.progress ? <ProgressBar done={field.progress.done} total={field.progress.total} /> : null}
      {field.style === 'objects' && field.objects ? (
        <ObjectCards
          objects={field.objects}
          itemStates={itemStates}
          entityStates={entityStates}
          onSetItemState={onSetItemState}
          onSetEntityState={onSetEntityState}
        />
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
 * Recipe serving scaling — reads `structuredData` directly rather than going
 * through `buildDetailModel`'s Ingredients field, because scaling needs the
 * structured `{name, quantity, note}` shape to recompute from, not the
 * already-flattened display string. Legacy flat-string saves (pre-2026-08-07)
 * render unscaled: there is no separate quantity to scale.
 */
function RecipeIngredients({ data }: { data: Record<string, unknown> }) {
  const { spacing } = useTheme();
  const base = baseServings(data.servings);
  const [servings, setServings] = useState<number | null>(base);
  const factor = servings && base ? servings / base : 1;

  const ingredients = Array.isArray(data.ingredients) ? data.ingredients : [];
  const items = ingredients
    .map((raw) => {
      if (typeof raw === 'string') {
        const trimmed = raw.trim();
        return trimmed && trimmed !== '[unclear]' ? trimmed : null;
      }
      if (raw && typeof raw === 'object') {
        const r = raw as { name?: unknown; quantity?: unknown; note?: unknown };
        const clean = (v: unknown) => (typeof v === 'string' && v.trim() && v.trim() !== '[unclear]' ? v.trim() : '');
        const name = clean(r.name);
        if (!name) return null;
        const rawQuantity = clean(r.quantity);
        const quantity = rawQuantity ? scaleQuantity(rawQuantity, factor) : '';
        const note = clean(r.note);
        return `${quantity ? `${quantity} ` : ''}${name}${note ? `, ${note}` : ''}`;
      }
      return null;
    })
    .filter((v): v is string => v !== null);

  if (!items.length) return null;

  const stepper = base ? (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm }}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel="Fewer servings"
        onPress={() => setServings((s) => Math.max(1, (s ?? base) - 1))}
        haptic="selection"
      >
        <Glyph name="minus" size={14} />
      </Touchable>
      <AppText variant="label">{servings ?? base} servings</AppText>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel="More servings"
        onPress={() => setServings((s) => (s ?? base) + 1)}
        haptic="selection"
      >
        <Glyph name="plus" size={14} />
      </Touchable>
    </View>
  ) : undefined;

  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel trailing={stepper}>Ingredients</SectionLabel>
      <Chips items={items} />
    </View>
  );
}

/**
 * Step-at-a-time cook mode. `useKeepAwake` holds the screen on while mounted
 * and releases it automatically on unmount — the whole reason for the
 * dependency, per `docs/next-phases.md` §4.2.
 */
function CookMode({ steps, onClose }: { steps: string[]; onClose: () => void }) {
  useKeepAwake();
  const { palette, spacing, icon } = useTheme();
  const [index, setIndex] = useState(0);
  const isLast = index === steps.length - 1;

  return (
    <View
      style={[
        StyleSheet.absoluteFill,
        { backgroundColor: palette.background, padding: spacing.xl, justifyContent: 'space-between' },
      ]}
    >
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <AppText variant="sectionLabel" tone="muted">
          Step {index + 1} of {steps.length}
        </AppText>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Close cook mode"
          onPress={onClose}
          haptic="light"
          style={{
            width: 32,
            height: 32,
            borderRadius: 16,
            backgroundColor: palette.surfaceVariant,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name="close" size={icon.sm} />
        </Touchable>
      </View>

      <AppText variant="display" style={{ textAlign: 'center', lineHeight: 34 }}>
        {steps[index]}
      </AppText>

      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Previous step"
          disabled={index === 0}
          onPress={() => setIndex((i) => Math.max(0, i - 1))}
          haptic="light"
        >
          <AppText variant="label" tone={index === 0 ? 'muted' : 'accent'}>
            Back
          </AppText>
        </Touchable>
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={isLast ? 'Finish cook mode' : 'Next step'}
          onPress={() => (isLast ? onClose() : setIndex((i) => Math.min(steps.length - 1, i + 1)))}
          haptic="medium"
        >
          <AppText variant="label" tone="accent">
            {isLast ? 'Done' : 'Next'}
          </AppText>
        </Touchable>
      </View>
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
    // Pipeline ran fine — the content just had nothing extractable
    // (login wall, video-only Short, empty transcript, etc.).
    unusable: {
      title: 'Nothing to extract here',
      body: "Weavr read this content but couldn't pull out any useful information — it may be behind a login wall, have no transcript, or contain only media.",
    },
  };

  // For unusable saves (now status=ready), key off knowledgeType.
  const key = save.knowledgeType === 'unusable' ? 'unusable' : save.status;
  const { title, body } = copy[key] ?? copy.processing;

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
function AddToShoppingList({ saveId, spaceId }: { saveId: string; spaceId?: string }) {
  const { palette, radius, spacing, icon } = useTheme();
  const router = useRouter();
  const [state, setState] = useState<'idle' | 'adding' | 'added' | 'error'>('idle');
  const [message, setMessage] = useState<string | null>(null);

  /**
   * S4: the target follows the *save*, not the caller — a recipe in a Space
   * feeds that Space's shared list. The copy has to say so, because the
   * difference is not visible anywhere else on this screen and "your shopping
   * list" would be a lie about where the ingredients just went.
   */
  const shared = !!spaceId;
  const listRoute = spaceId
    ? ({ pathname: '/space/[id]/shopping-list', params: { id: spaceId } } as const)
    : ('/shopping-list' as const);

  // Queued rather than awaited: the conversion is a *server* job that spends a
  // Gemini request, so nothing about waiting here told the user anything the
  // "Adding…" copy did not. Queueing it means the same tap works on a train, and
  // a rejection surfaces through the queue like every other terminal failure.
  const add = () => {
    setState('added');
    setMessage(null);
    writeConvertToShoppingList(saveId);
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
            {shared ? 'Adding to the Space’s shopping list…' : 'Adding to your shopping list…'}
          </AppText>
          <Touchable
            accessibilityRole="button"
            onPress={() => router.push(listRoute)}
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
        accessibilityLabel={
          shared
            ? 'Add this recipe to the Space’s shopping list'
            : 'Add this recipe to your shopping list'
        }
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
          {state === 'adding'
            ? 'Adding…'
            : shared
              ? 'Add to the shared shopping list'
              : 'Add to shopping list'}
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

  const [showSpaceSheet, setShowSpaceSheet] = useState(false);
  const [cookModeOpen, setCookModeOpen] = useState(false);

  // The save comes from the local store, so opening a card is instant from
  // anywhere — the feed, the Library, a group, a search result, a cold deep
  // link — rather than only from screens that happened to hold a copy. The
  // pull below fills in anything that changed behind it.
  const { data: stored, loading: reading } = useLive<SaveResponse | null>(
    ['saves', 'item_states'],
    (store) => store.readSave(id),
    [id],
  );
  const save = stored ?? null;
  const [error, setError] = useState<ApiError | null>(null);

  // Text-note inline editor state. Initialised from the save's structuredData
  // and kept in sync whenever the save refreshes (e.g., after a successful save).
  const [noteTitle, setNoteTitle] = useState('');
  const [noteBody, setNoteBody] = useState('');
  const [noteSaving, setNoteSaving] = useState(false);

  // Sync editor fields whenever the underlying save changes.
  useEffect(() => {
    if (!save || save.sourceType !== 'text') return;
    setNoteTitle((save.structuredData?.title as string | undefined) ?? '');
    setNoteBody(
      (save.structuredData?.body as string | undefined) ??
      save.rawCaption ??
      '',
    );
  }, [save]);

  const load = useCallback(async () => {
    const pulled = await sync.pullSave(id);
    // Only an error when there is nothing local to show instead — offline on a
    // save you already have is not a failure, it is the point of this layer.
    setError(pulled == null && stored == null ? new ApiError('server', 'Something went wrong', null) : null);
  }, [id, stored]);

  useEffect(() => {
    void sync.pullSave(id);
  }, [id]);

  const loading = reading && save == null;

  /**
   * K2: this save's items' entity state, keyed by `Entities.key`'s output —
   * read from `entity_states` rather than from a save's own `itemStates`,
   * because it survives the same entity appearing in a later save.
   *
   * It used to be a `GET /v1/collections/recommendation_list` on mount purely
   * to harvest the `state` off each merged entity. The sync engine already
   * does that harvest (`sync.syncEntityStates`), so this is now a local read
   * of the whole map — cheap enough not to need the type filter that request
   * had.
   */
  const entityStates = useLiveValue<EntityStates>(
    ['entity_states'],
    (store) => store.readEntityStates(),
    undefined,
  );

  /**
   * The one handler behind every knowledge type's object behavior. Optimistic
   * because the controls it drives (a tick, a star) are exactly the kind of
   * thing that should feel instant — `SaveItemStateService`'s "full replace,
   * never a merge" contract makes the echoed response safe to just adopt
   * wholesale rather than reconciling it against local state.
   */
  const setItemState = useCallback(
    (itemPath: string, state: Record<string, unknown>) => {
      if (!save) return;
      // Through the store, so the Home rail's course progress and the Library's
      // card for this same save update with it — and then through the outbox, so
      // a tick made offline is sent rather than lost. The "reload and let the
      // server decide" fallback this used to have is gone: it could not run
      // offline, which is precisely when it was needed.
      writeSaveItemState(save, itemPath, state);
    },
    [save],
  );

  /**
   * K2's counterpart to `setItemState` — watched/rating for a
   * `recommendation_list` item, keyed by entity rather than by this save's
   * item path, so it survives the same title appearing in a later save.
   * Same optimistic shape, now written through the store: flip it locally,
   * PATCH, adopt the echo — and because the collection screen reads the same
   * `entity_states` table, marking something watched here is already reflected
   * there without either screen knowing about the other.
   */
  const setEntityState = useCallback((entityKey: string, state: Record<string, unknown>) => {
    writeEntityState(entityKey, state);
  }, []);

  const model = save ? buildDetailModel(save, entityStates) : null;
  const isRecipe = save?.knowledgeType === 'recipe';
  const isTextNote = save?.sourceType === 'text';
  const isImageSave = save?.sourceType === 'image';
  // Ingredients render through `RecipeIngredients` for recipes (it needs the
  // raw structured shape to scale by servings) rather than the model's
  // already-flattened chip strings.
  const displayFields = model ? (isRecipe ? model.fields.filter((f) => f.label !== 'Ingredients') : model.fields) : [];

  // Whether the editor fields differ from what's persisted.
  const noteDirty =
    isTextNote &&
    (
      noteTitle.trim() !== ((save?.structuredData?.title as string | undefined) ?? '') ||
      noteBody.trim() !== ((save?.structuredData?.body as string | undefined) ?? save?.rawCaption ?? '')
    );

  /**
   * Persist title + body.
   *
   * Local first, and there is no `catch` because there is nothing useful to do
   * in one: the edit is in the store and in the queue before this returns, and a
   * request that cannot be delivered is retried rather than losing the user's
   * writing. The old version kept the text "in the local state so the user can
   * retry", which only held until the screen unmounted.
   */
  const saveNote = useCallback(() => {
    if (!save || noteSaving) return;
    setNoteSaving(true);
    writeNote(save, noteTitle.trim() || undefined, noteBody.trim() || undefined);
    setNoteSaving(false);
  }, [save, noteTitle, noteBody, noteSaving]);

  const recipeSteps =
    isRecipe && Array.isArray(save?.structuredData?.steps)
      ? (save.structuredData.steps as unknown[]).filter((s): s is string => typeof s === 'string' && s.trim().length > 0)
      : [];

  return (
    <>
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
              {isTextNote ? (
                /* NOTE badge — a fixed warm dot + label */
                <>
                  <View
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: 4,
                      backgroundColor: TYPE_COLORS.other,
                    }}
                  />
                  <AppText variant="sectionLabel" tone="muted">NOTE</AppText>
                </>
              ) : isImageSave ? (
                /* SCREENSHOT badge */
                <>
                  <View
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: 4,
                      backgroundColor: TYPE_COLORS.other,
                    }}
                  />
                  <AppText variant="sectionLabel" tone="muted">
                    {save.knowledgeType ? save.knowledgeType.toUpperCase() : 'SCREENSHOT'}
                  </AppText>
                </>
              ) : save.knowledgeType ? (
                <>
                  <View
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: 4,
                      backgroundColor: TYPE_COLORS[save.knowledgeType] ?? TYPE_COLORS.other,
                    }}
                  />
                  <AppText variant="sectionLabel" tone="muted">
                    {save.knowledgeType ?? STATUS_LABELS[save.status]}
                  </AppText>
                </>
              ) : (
                <AppText variant="sectionLabel" tone="muted">
                  {STATUS_LABELS[save.status]}
                </AppText>
              )}
            </View>

            {isTextNote ? (
              /* Editable title for text notes */
              <TextInput
                value={noteTitle}
                onChangeText={setNoteTitle}
                placeholder="Title"
                placeholderTextColor={palette.textFaint}
                style={{
                  fontSize: 26,
                  fontWeight: '700',
                  color: palette.text,
                  marginTop: spacing.xs,
                  marginBottom: spacing.xs,
                  paddingVertical: 0,
                }}
              />
            ) : (
              <AppText variant="display" style={{ marginTop: spacing.xs, marginBottom: spacing.xs }}>
                {model?.title ?? saveTitle(save)}
              </AppText>
            )}

            {!isTextNote && model?.meta ? (
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

          {/* Full-width screenshot image — shown for IMAGE saves so users
              can see their original screenshot alongside the extracted data. */}
          {isImageSave && save.thumbnailUrl ? (
            <Reveal index={2}>
              <Image
                source={{ uri: save.thumbnailUrl }}
                style={{
                  width: '100%',
                  height: undefined,
                  aspectRatio: 9 / 16,
                  borderRadius: radius.lg,
                  marginBottom: spacing.xl,
                  backgroundColor: palette.surface,
                }}
                resizeMode="contain"
              />
            </Reveal>
          ) : null}

          {/* The only Act that exists, and only recipes have it. Placed above
              the fields because it is the reason to open a recipe at all. */}
          {model && save.knowledgeType === 'recipe' ? (
            <Reveal index={2}>
              <AddToShoppingList saveId={save.id} spaceId={save.spaceId} />
            </Reveal>
          ) : null}

          {/* Cook mode: step-at-a-time with the screen held awake. Only offered
              when there is something to step through. */}
          {model && isRecipe && recipeSteps.length > 0 ? (
            <Reveal index={2}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Start cook mode"
                onPress={() => setCookModeOpen(true)}
                haptic="medium"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: spacing.smd,
                  paddingVertical: spacing.md,
                  borderRadius: radius.sm,
                  borderWidth: 1,
                  borderColor: palette.border,
                  backgroundColor: palette.surface,
                  marginBottom: spacing.smd,
                }}
              >
                <Glyph name="utensils" size={16} weight={2} color={palette.textMuted} />
                <AppText variant="label" tone="muted">
                  Start cook mode
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {/* place's "Open in Maps" — pure URL construction, no state. */}
          {model?.mapsUrl ? (
            <Reveal index={2}>
              <Touchable
                accessibilityRole="link"
                accessibilityLabel="Open in Maps"
                onPress={() => void Linking.openURL(model.mapsUrl as string).catch(() => {})}
                haptic="medium"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: spacing.smd,
                  paddingVertical: spacing.md,
                  borderRadius: radius.sm,
                  borderWidth: 1,
                  borderColor: palette.border,
                  backgroundColor: palette.surface,
                  marginBottom: spacing.smd,
                }}
              >
                <Glyph name="mapPin" size={16} weight={2} color={palette.textMuted} />
                <AppText variant="label" tone="muted">
                  Open in Maps
                </AppText>
              </Touchable>
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

          {model && isRecipe ? (
            <Reveal index={3}>
              <RecipeIngredients data={save.structuredData ?? {}} />
            </Reveal>
          ) : null}

          {isTextNote ? (
            /* Editable body for text notes — the full note content. */
            <Reveal index={3}>
              <SectionLabel>Note</SectionLabel>
              <TextInput
                value={noteBody}
                onChangeText={setNoteBody}
                placeholder="Write your note…"
                placeholderTextColor={palette.textFaint}
                multiline
                textAlignVertical="top"
                style={{
                  minHeight: 180,
                  fontSize: 15,
                  lineHeight: 23,
                  color: palette.text,
                  backgroundColor: palette.surfaceVariant,
                  borderRadius: radius.md,
                  borderWidth: 1,
                  borderColor: palette.border,
                  padding: spacing.md,
                  marginBottom: spacing.smd,
                }}
              />
            </Reveal>
          ) : model ? (
            displayFields.map((field, i) => (
              <Reveal key={field.label} index={3 + i}>
                <Field
                  field={field}
                  itemStates={save.itemStates}
                  entityStates={entityStates}
                  onSetItemState={setItemState}
                  onSetEntityState={setEntityState}
                />
              </Reveal>
            ))
          ) : (
            <Reveal index={2}>
              <UnfinishedSave save={save} />
            </Reveal>
          )}

          {/* Save changes button — only visible for text notes with unsaved edits. */}
          {isTextNote && noteDirty ? (
            <Reveal index={4}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Save changes"
                onPress={saveNote}
                haptic="medium"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: spacing.sm,
                  paddingVertical: spacing.md,
                  borderRadius: radius.sm,
                  borderWidth: 1,
                  borderColor: 'transparent',
                  backgroundColor: palette.accent,
                  marginBottom: spacing.smd,
                }}
              >
                {noteSaving ? (
                  <ActivityIndicator size="small" color={palette.onAccent} />
                ) : (
                  <Glyph name="check" size={16} weight={2.5} color={palette.onAccent} />
                )}
                <AppText variant="label" style={{ color: palette.onAccent }}>
                  {noteSaving ? 'Saving…' : 'Save changes'}
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {/* Progress only makes sense once there is something to make
              progress on — a save still being processed has no content yet. */}
          {save.status === 'ready' ? (
            <Reveal index={3 + (displayFields.length ?? 1)}>
              {/* No `onChange` reload: the strip writes through the store, so
                  `save.lifecycleStatus` above is already the new value on the
                  next render — re-fetching would only confirm what we wrote. */}
              <LifecycleStrip saveId={save.id} value={save.lifecycleStatus ?? 'saved'} />
            </Reveal>
          ) : null}

          {/* Renders nothing when there is nothing similar enough — the
              server's distance cutoff, not a loading placeholder. */}
          {save.status === 'ready' ? <RelatedRail saveId={save.id} /> : null}

          {/* Renders nothing for a private save: a comment thread only you can
              see is a note to self, not a discussion. */}
          <Discussion saveId={save.id} spaceId={save.spaceId} />

          {save.sourceUrl ? (
            <Reveal index={4 + (displayFields.length ?? 1)}>
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
    {cookModeOpen && recipeSteps.length > 0 ? (
      <CookMode steps={recipeSteps} onClose={() => setCookModeOpen(false)} />
    ) : null}
    </>
  );
}
