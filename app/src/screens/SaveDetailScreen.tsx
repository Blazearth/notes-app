import { useRouter } from 'expo-router';
import { useKeepAwake } from 'expo-keep-awake';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ActivityIndicator, Image, Linking, Modal, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withRepeat,
  withSequence,
  withTiming,
} from 'react-native-reanimated';

import { ApiError } from '@/api/client';
import type { SaveResponse, Space } from '@/api/types';
import { track } from '@/analytics/client';
import { AnalyticsEvent, daysSinceCapture } from '@/analytics/events';
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
import {
  buildDetailModel,
  type DetailField,
  type DetailObject,
  type DetailObjectRow,
  type DetailFieldGroup,
  type EntityStates,
} from '@/saves/detailModel';
import { bareUrl, STATUS_LABELS, saveTitle, sourceKindLabel, sourcePlatformName } from '@/saves/format';
import { canRetry, isRetrying, resolveRetry, retryTarget, startRetry } from '@/saves/retry';
import { useSaves } from '@/saves/SavesProvider';
import { baseServings, scaleQuantity } from '@/saves/scaling';
import { groupItemNoun, saveTypeMeta } from '@/saves/saveTypeMeta';
import { FACETS, isUsableFacetValue } from '@/knowledge/facets';
import { spaceIdentity } from '@/spaces/spaceMeta';
import { TYPE_COLORS } from '@/theme/palettes';
import { useTheme } from '@/theme/ThemeProvider';
import { AddToSpaceSheet } from './AddToSpaceSheet';
import { ReminderSheet } from '@/components/ReminderSheet';
import { getScheduledReminderForSave } from '@/notifications/notifications';

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

/** One tile in the "You might also like" rail — thumbnail, title, meta, nothing else. */
function RelatedCard({ save, onPress }: { save: SaveResponse; onPress: () => void }) {
  const { radius, spacing } = useTheme();
  const typeMeta = save.knowledgeType ? saveTypeMeta(save.knowledgeType) : undefined;
  const model = buildCardModel(save);
  return (
    <Card padding={0} radius={radius.lg} style={{ width: 132, overflow: 'hidden' }}>
      <Touchable accessibilityRole="button" onPress={onPress} haptic="selection">
        <SaveThumb
          thumbnailUrl={save.thumbnailUrl}
          width={132}
          height={68}
          radius={0}
          tint={typeMeta?.color}
          glyph={typeMeta?.glyph}
        />
        <View style={{ padding: spacing.sm }}>
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
 * The one line under the rail explaining *why* these showed up — grounded in
 * what was actually fetched, never a generated claim. When the current save's
 * own facet value (genre, cuisine, …) is shared by at least one of the
 * results, name it and count how many results share the save's own knowledge
 * type; otherwise fall back to a plain type-level line rather than inventing
 * a reason the data doesn't support.
 */
function relatedReason(save: SaveResponse, related: SaveResponse[]): string {
  const type = save.knowledgeType;
  if (!type) return 'Similar to what you’ve saved';

  const sameType = related.filter((r) => r.knowledgeType === type);
  if (sameType.length === 0) return `Similar to what you’ve saved`;

  const facetField = FACETS[type];
  const rawFacet = facetField ? save.structuredData?.[facetField] : undefined;
  const facetValue = Array.isArray(rawFacet)
    ? rawFacet.find(isUsableFacetValue)
    : isUsableFacetValue(rawFacet)
      ? rawFacet
      : undefined;

  const noun = groupItemNoun(type, sameType.length);
  return facetValue
    ? `Because you saved ${sameType.length} ${facetValue.toLowerCase()} ${noun}`
    : `Because you saved ${sameType.length} similar ${noun}`;
}

/**
 * "You might also like…" — Phase 5 §5.3. Fetches lazily, only once the save
 * is `ready` (an unclassified save has no embedding to compare against), and
 * renders nothing on an empty result: the server's distance cutoff means an
 * empty array is "nothing genuinely similar," the same honest-empty-state
 * rule the search screen already follows, not a loading or error state.
 */
function RelatedRail({ save }: { save: SaveResponse }) {
  const { spacing } = useTheme();
  const router = useRouter();
  const [related, setRelated] = useState<SaveResponse[]>([]);

  useEffect(() => {
    let cancelled = false;
    repo
      .getRelatedSaves(save.id)
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
  }, [save.id]);

  if (related.length === 0) return null;

  return (
    <View style={{ marginBottom: 24 }}>
      <SectionLabel>You might also like</SectionLabel>
      <Rail>
        {related.map((r) => (
          <RelatedCard
            key={r.id}
            save={r}
            onPress={() => router.push({ pathname: '/save/[id]', params: { id: r.id } })}
          />
        ))}
      </Rail>
      <AppText variant="caption" tone="muted" style={{ marginTop: spacing.sm }}>
        {relatedReason(save, related)}
      </AppText>
    </View>
  );
}

/**
 * An itinerary place's "Open in Maps" deep link — pure URL, no state.
 *
 * Icon-only, sitting in the card's header rather than a labelled row below
 * the content: with 30+ places on a destination, "Open in Maps" repeated as
 * text down the whole screen is the loudest thing on it despite being the
 * least interesting fact about any single place. The pin glyph is universal
 * enough not to need the label restated, and `accessibilityLabel` keeps it
 * legible to a screen reader regardless.
 */
function MapsButton({ url }: { url: string }) {
  const { palette, radius } = useTheme();
  return (
    <Touchable
      accessibilityRole="link"
      accessibilityLabel="Open in Maps"
      onPress={() => void Linking.openURL(url).catch(() => {})}
      haptic="light"
      style={{
        width: 30,
        height: 30,
        borderRadius: radius.sm,
        backgroundColor: palette.surface,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <Glyph name="mapPin" size={14} color={palette.accent} />
    </Touchable>
  );
}

/**
 * Rows without a chip list collapse into one compact secondary line —
 * "feel-good movie · Netflix" rather than a labelled block per row. The label
 * ("Why", "Where") is dropped: the value alone reads fine in sequence, and
 * keeping the label per row is what made the old card tall.
 */
function compactRowLine(rows: DetailObjectRow[]): string | undefined {
  const parts = rows.filter((r): r is DetailObjectRow & { text: string } => !!r.text);
  return parts.length ? parts.map((r) => r.text).join(' · ') : undefined;
}

/**
 * One entry of a nested object array — an exercise, a watchlist item, a
 * checklist row. Thumbnail (when the object has one) and content sit side by
 * side rather than stacked, and every plain-text row folds into one compact
 * line under the title — a chip-list row (genre, cues, tips) is the only kind
 * that still gets its own line, since a wrapped array needs room a joined
 * string doesn't. Whichever Phase 4 control the object carries
 * (`docs/next-phases.md` §4.2) sits directly under that, still on the card.
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
    <View style={{ gap: spacing.sm }}>
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
        const chipRows = object.rows.filter((r) => r.items && r.items.length > 0);
        const line = compactRowLine(object.rows);
        const hasControl =
          (object.control === 'check' || object.control === 'watch') && (object.statePath || object.entityKey);
        // `mapsUrl` is deliberately not part of this: it renders as a small
        // icon in the header instead of a labelled row, so a list of dozens
        // of places doesn't repeat "Open in Maps" in text down the screen.
        const hasActionRow = hasControl || object.restLabel;

        return (
          <View
            key={`${object.title ?? 'item'}-${i}`}
            style={{
              flexDirection: 'row',
              padding: spacing.smd,
              borderRadius: radius.md,
              backgroundColor: palette.surfaceVariant,
              borderWidth: 1,
              borderColor: palette.border,
              gap: spacing.smd,
            }}
          >
            {object.imageUrl ? (
              <Image
                source={{ uri: object.imageUrl }}
                style={{ width: 40, height: 58, borderRadius: radius.sm }}
                resizeMode="cover"
              />
            ) : null}
            <View style={{ flex: 1, gap: 1 }}>
              {object.title ? (
                <AppText variant="cardTitle" numberOfLines={1}>
                  {object.title}
                </AppText>
              ) : null}
              {object.meta ? (
                <AppText variant="caption" tone="muted" numberOfLines={1}>
                  {object.meta}
                </AppText>
              ) : null}
              {line ? (
                <AppText variant="bodySmall" tone="muted" numberOfLines={2} style={{ marginTop: 1 }}>
                  {line}
                </AppText>
              ) : null}
              {chipRows.map((row) => (
                <View
                  key={row.label}
                  style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 4, marginTop: 4 }}
                >
                  <AppText variant="caption" tone="faint">
                    {row.label}
                  </AppText>
                  <Chips items={row.items ?? []} />
                </View>
              ))}
              {hasActionRow ? (
                <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.md }}>
                  {object.control === 'check' && hasControl ? (
                    <CheckControl
                      done={state?.done === true}
                      onToggle={() => applyChange({ ...state, done: state?.done !== true })}
                    />
                  ) : null}
                  {object.control === 'watch' && hasControl ? (
                    <WatchControl state={state} onChange={applyChange} />
                  ) : null}
                  {object.restLabel ? <RestTimer label={object.restLabel} /> : null}
                </View>
              ) : null}
            </View>
            {object.mapsUrl ? <MapsButton url={object.mapsUrl} /> : null}
          </View>
        );
      })}
    </View>
  );
}

/**
 * The clustered form of `ObjectCards` — an itinerary's places under "Tokyo",
 * "Mt. Fuji", "Kyoto" headings instead of one 45-card list. A thin wrapper
 * rather than a second card renderer: each group is still just `ObjectCards`
 * with a small heading above it, so a place's own card (thumbnail, tips,
 * "Open in Maps") is identical either way.
 */
function GroupedObjectCards({
  groups,
  itemStates,
  entityStates,
  onSetItemState,
  onSetEntityState,
}: {
  groups: DetailFieldGroup[];
  itemStates: ItemStates;
  entityStates: EntityStates;
  onSetItemState: SetItemState;
  onSetEntityState: SetEntityState;
}) {
  const { spacing } = useTheme();
  return (
    <View style={{ gap: spacing.lg }}>
      {groups.map((group) => (
        <View key={group.label}>
          <AppText variant="label" style={{ marginBottom: spacing.sm }}>
            {group.label}
          </AppText>
          <ObjectCards
            objects={group.objects}
            itemStates={itemStates}
            entityStates={entityStates}
            onSetItemState={onSetItemState}
            onSetEntityState={onSetEntityState}
          />
        </View>
      ))}
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
      {field.style === 'objects' && field.groups ? (
        <GroupedObjectCards
          groups={field.groups}
          itemStates={itemStates}
          entityStates={entityStates}
          onSetItemState={onSetItemState}
          onSetEntityState={onSetEntityState}
        />
      ) : field.style === 'objects' && field.objects ? (
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
 * Three dots pulsing in staggered sequence — signals "still working, no
 * action needed" without claiming literal per-step progress the client has
 * no way to observe. Mirrors `Skeleton`'s reanimated idiom and respects
 * reduced-motion the same way.
 */
function PulsingDots() {
  const { palette } = useTheme();
  const reduced = useReducedMotion();
  const d1 = useSharedValue(reduced ? 0.7 : 0.3);
  const d2 = useSharedValue(reduced ? 0.7 : 0.3);
  const d3 = useSharedValue(reduced ? 0.7 : 0.3);

  useEffect(() => {
    if (reduced) return;
    const pulse = (v: typeof d1, delay: number) => {
      v.value = withDelay(
        delay,
        withRepeat(withSequence(withTiming(1, { duration: 400 }), withTiming(0.3, { duration: 400 })), -1, true),
      );
    };
    pulse(d1, 0);
    pulse(d2, 150);
    pulse(d3, 300);
  }, [reduced, d1, d2, d3]);

  const s1 = useAnimatedStyle(() => ({ opacity: d1.value }));
  const s2 = useAnimatedStyle(() => ({ opacity: d2.value }));
  const s3 = useAnimatedStyle(() => ({ opacity: d3.value }));

  return (
    <View style={{ flexDirection: 'row', gap: 5 }}>
      {[s1, s2, s3].map((style, i) => (
        <Animated.View
          key={i}
          style={[{ width: 6, height: 6, borderRadius: 3, backgroundColor: palette.textMuted }, style]}
        />
      ))}
    </View>
  );
}

/**
 * What Weavr broadly does to a save, as a calm static list — not a literal
 * per-item tracker, because the client has no signal for which stage is
 * actually running. Claiming step 1 is "done" while step 2 is "active" would
 * be inventing progress the pipeline never reported.
 */
const PROCESSING_STAGES = ['Reading source', 'Extracting details', 'Organizing results'];

function StageList() {
  const { palette, spacing } = useTheme();
  return (
    <View style={{ gap: 6 }}>
      {PROCESSING_STAGES.map((stage) => (
        <View key={stage} style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}>
          <View style={{ width: 4, height: 4, borderRadius: 2, backgroundColor: palette.textFaint }} />
          <AppText variant="bodySmall" tone="muted">
            {stage}
          </AppText>
        </View>
      ))}
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
 *
 * Never tells the user to refresh anything: `SavesProvider` already polls a
 * `processing` save every couple of seconds and writes the result straight
 * into the local store, so this screen's own `useLive` subscription re-renders
 * it the moment the status changes — no action needed, so none is asked for.
 */
function UnfinishedSave({ save }: { save: SaveResponse }) {
  const { palette, spacing, radius } = useTheme();
  const router = useRouter();
  const { saves } = useSaves();
  const [retrying, setRetrying] = useState(() => isRetrying(save.id));
  const target = retrying ? retryTarget(save.id, saves) : undefined;

  // Same mechanism as the feed's `SaveCard` retry: watches the linked attempt
  // through the same live store every screen reads, advancing the instant the
  // pipeline moves the new save to `ready` or `failed`.
  useEffect(() => {
    if (!retrying || !target) return;
    if (target.status === 'ready') {
      resolveRetry(save.id, target, true);
      // A successful retry deletes *this* row (see `saves/retry.ts`), so
      // staying put would leave the screen pointed at a save that no longer
      // exists. Hand off to the new one instead of re-rendering into a void.
      router.replace({ pathname: '/save/[id]', params: { id: target.id } });
    } else if (target.status === 'failed') {
      resolveRetry(save.id, target, false);
      setRetrying(false);
    }
  }, [retrying, target, save.id, router]);

  const handleRetry = () => {
    if (retrying) return;
    if (startRetry(save, saves)) setRetrying(true);
  };

  if (save.knowledgeType === 'unusable') {
    // Pipeline ran fine — the content just had nothing extractable (login
    // wall, video-only Short, empty transcript, etc.).
    return (
      <Card>
        <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
          Nothing to extract here
        </AppText>
        <AppText variant="bodySmall" tone="muted">
          Weavr read this content but couldn't pull out any useful information — it may be behind a login wall,
          have no transcript, or contain only media.
        </AppText>
      </Card>
    );
  }

  if (save.status === 'pending') {
    return (
      <Card>
        <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
          Queued for tomorrow
        </AppText>
        <AppText variant="bodySmall" tone="muted">
          Today's AI budget is spent, so this save is waiting for the next window. Nothing is lost — it will be
          processed automatically.
        </AppText>
      </Card>
    );
  }

  if (save.status === 'failed') {
    return (
      <Card>
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.md }}>
          <View style={{ flex: 1 }}>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs, color: palette.danger }}>
              What went wrong
            </AppText>
            <AppText variant="bodySmall" tone="muted">
              {save.errorMessage ?? 'Something went wrong and Weavr could not extract anything useful.'}
            </AppText>
            {save.errorCode ? (
              <AppText variant="caption" tone="muted" style={{ marginTop: spacing.md, color: palette.textFaint }}>
                {save.errorCode}
              </AppText>
            ) : null}
          </View>
          {retrying ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.xs }}>
              <ActivityIndicator size="small" color={palette.textMuted} />
              <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
                Retrying…
              </AppText>
            </View>
          ) : canRetry(save) ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Retry"
              onPress={handleRetry}
              haptic="selection"
              style={{
                paddingVertical: spacing.xs,
                paddingHorizontal: spacing.smd,
                borderRadius: radius.pill,
                borderWidth: 1,
                borderColor: palette.border,
              }}
            >
              <AppText variant="label" tone="accent" style={{ fontSize: 12 }}>
                Retry
              </AppText>
            </Touchable>
          ) : null}
        </View>
      </Card>
    );
  }

  // `processing` — the only state left.
  return (
    <Card>
      <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
        Reading your source…
      </AppText>
      <AppText variant="bodySmall" tone="muted" style={{ marginBottom: spacing.lg }}>
        Weavr is extracting the useful details. This usually takes a few seconds.
      </AppText>
      <View style={{ marginBottom: spacing.md }}>
        <PulsingDots />
      </View>
      <StageList />
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

/**
 * The original screenshot, as a compact media card rather than a near-full-
 * screen image. It's real context for why Weavr filed this the way it did —
 * worth keeping — but it isn't the reason the user opened this save, so it
 * shouldn't outweigh the extracted content. Tapping it (or "View original")
 * opens a full-screen lightbox for the one case that does need the whole
 * picture.
 */
function SourcePreviewCard({ uri }: { uri: string }) {
  const { palette, radius, spacing } = useTheme();
  const [expanded, setExpanded] = useState(false);

  return (
    <View style={{ marginBottom: spacing.lg }}>
      <SectionLabel>Source context</SectionLabel>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel="View original screenshot"
        onPress={() => setExpanded(true)}
        haptic="light"
        weight="card"
        style={{ borderRadius: radius.lg, overflow: 'hidden', backgroundColor: palette.surface }}
      >
        <Image
          source={{ uri }}
          style={{ width: '100%', height: 180 }}
          resizeMode="cover"
        />
      </Touchable>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel="View original screenshot"
        onPress={() => setExpanded(true)}
        haptic="light"
        style={{ alignSelf: 'flex-start', marginTop: spacing.xs }}
      >
        <AppText variant="bodySmall" tone="accent">
          View original ↗
        </AppText>
      </Touchable>

      <Modal visible={expanded} transparent animationType="fade" onRequestClose={() => setExpanded(false)}>
        <Pressable
          style={{ flex: 1, backgroundColor: 'rgba(0,0,0,0.9)', alignItems: 'center', justifyContent: 'center' }}
          onPress={() => setExpanded(false)}
        >
          <Image
            source={{ uri }}
            style={{ width: '92%', height: '80%' }}
            resizeMode="contain"
          />
          <Touchable
            accessibilityRole="button"
            accessibilityLabel="Close"
            onPress={() => setExpanded(false)}
            haptic="light"
            style={{
              position: 'absolute',
              top: 48,
              right: 20,
              width: 36,
              height: 36,
              borderRadius: 18,
              backgroundColor: 'rgba(255,255,255,0.15)',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Glyph name="close" size={16} color="#fff" />
          </Touchable>
        </Pressable>
      </Modal>
    </View>
  );
}

export function SaveDetailScreen({ id }: { id: string }) {
  const { palette, radius, spacing } = useTheme();

  const [showSpaceSheet, setShowSpaceSheet] = useState(false);
  const [showReminderSheet, setShowReminderSheet] = useState(false);
  const [hasReminder, setHasReminder] = useState(false);
  const [cookModeOpen, setCookModeOpen] = useState(false);

  const checkReminder = useCallback(() => {
    getScheduledReminderForSave(id).then((res) => {
      setHasReminder(!!res);
    });
  }, [id]);

  useEffect(() => {
    checkReminder();
  }, [checkReminder]);

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

  // `save_viewed` — whether "ready" ever gets acted on. Keyed on `id` alone
  // (not on `save`, which changes shape as fields fill in) so a re-render
  // from an unrelated field update never double-fires this.
  useEffect(() => {
    if (!save || save.status !== 'ready') return;
    track(AnalyticsEvent.SaveViewed, {
      knowledge_type: save.knowledgeType ?? 'unknown',
      days_since_capture: daysSinceCapture(save.createdAt),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, save?.status]);

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
   * The Space this save currently lives in, if any — so "Add to Space" can
   * confirm *where* rather than leaving the tap's result to be inferred.
   */
  const currentSpace = useLiveValue<Space | null>(
    ['spaces'],
    (store) => (save?.spaceId ? store.readSpace(save.spaceId) : Promise.resolve(null)),
    null,
    [save?.spaceId],
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
  // Before the pipeline has classified anything, `saveTitle`'s fallback is
  // the bare host+path of the URL — technically accurate, but a wall of raw
  // URL as the hero headline reads as broken rather than "still working".
  // "Saving this Short" names what's actually happening instead; a failed
  // save gets the past-tense verb so the hero doesn't read as still in
  // progress right above a card that says otherwise.
  const unfinishedHeadline =
    save && !model && !isTextNote
      ? `${save.status === 'failed' ? "Couldn't save" : 'Saving'} this ${sourceKindLabel(save)}`
      : undefined;
  const unfinishedSubtitle =
    unfinishedHeadline && save?.sourceUrl
      ? [sourcePlatformName(save.sourceUrl), sourceKindLabel(save) !== 'link' ? sourceKindLabel(save) : null]
          .filter((p): p is string => !!p)
          .join(' ')
      : undefined;
  // Ingredients render through `RecipeIngredients` for recipes (it needs the
  // raw structured shape to scale by servings) rather than the model's
  // already-flattened chip strings. Bespoke fields and generic leftovers
  // (Category, page count, …) render at the same priority — nothing that
  // helps explain the item gets tucked behind a disclosure.
  const displayFields = model ? (isRecipe ? model.fields.filter((f) => f.label !== 'Ingredients') : model.fields) : [];

  // Whether there's a real, openable act already covering "what can I do with
  // this" — the shopping list, cook mode, or a Maps deep link. Only when none
  // of those exist does a plain "Open source" get promoted to a primary
  // button; a screenshot's `sourceUrl` is its own Supabase Storage object, not
  // a link worth surfacing as an action, so it's excluded even then.
  const hasBespokeAct = isRecipe || !!model?.mapsUrl;
  const showPrimaryOpenSource = !hasBespokeAct && !isImageSave && !!save?.sourceUrl;

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
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.md }}>
          <BackButton />
          {save && save.status === 'ready' ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel={hasReminder ? 'Reminder scheduled — tap to view' : 'Set reminder'}
              onPress={() => setShowReminderSheet(true)}
              haptic="light"
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                gap: spacing.xs,
                paddingVertical: spacing.xs + 2,
                paddingHorizontal: spacing.smd,
                borderRadius: radius.pill,
                borderWidth: 1,
                borderColor: hasReminder ? palette.accent : palette.border,
                backgroundColor: hasReminder ? `${palette.accent}14` : palette.surface,
              }}
            >
              <Glyph name="clock" size={14} weight={2} color={hasReminder ? palette.accent : palette.textMuted} />
              <AppText variant="caption" tone={hasReminder ? 'accent' : 'muted'} style={{ fontWeight: '600' }}>
                {hasReminder ? 'Reminder set' : 'Remind me'}
              </AppText>
            </Touchable>
          ) : null}
        </View>
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
            <View style={{ flexDirection: 'row', gap: spacing.md }}>
              {/* A small visual identity for the save — a real thumbnail when
                  one exists, otherwise the same tinted type-icon tile the
                  Library and the rail below already use. Skipped for notes
                  (no image is ever relevant) and screenshots (the real image
                  renders full-width just below instead — a second, smaller
                  copy of the same picture would be redundant). */}
              {!isTextNote && !isImageSave ? (
                <SaveThumb
                  thumbnailUrl={save.thumbnailUrl}
                  width={64}
                  height={88}
                  radius={radius.md}
                  tint={save.knowledgeType ? (TYPE_COLORS[save.knowledgeType] ?? TYPE_COLORS.other) : undefined}
                  glyph={save.knowledgeType ? saveTypeMeta(save.knowledgeType).glyph : undefined}
                />
              ) : null}

              <View style={{ flex: 1 }}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: 3 }}>
                  {isTextNote ? (
                    /* NOTE badge — a fixed warm dot + label */
                    <>
                      <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: TYPE_COLORS.other }} />
                      <AppText variant="sectionLabel" tone="muted">NOTE</AppText>
                    </>
                  ) : isImageSave ? (
                    /* SCREENSHOT badge */
                    <>
                      <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: TYPE_COLORS.other }} />
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
                      paddingVertical: 0,
                    }}
                  />
                ) : (
                  <AppText variant="display">{unfinishedHeadline ?? model?.title ?? saveTitle(save)}</AppText>
                )}

                {!isTextNote && (model?.meta ?? unfinishedSubtitle) ? (
                  <AppText variant="bodySmall" tone="muted" style={{ marginTop: 3 }}>
                    {model?.meta ?? unfinishedSubtitle}
                  </AppText>
                ) : null}
                {unfinishedSubtitle && save?.sourceUrl ? (
                  <AppText variant="caption" tone="faint" numberOfLines={1} style={{ marginTop: 2 }}>
                    {bareUrl(save.sourceUrl)}
                  </AppText>
                ) : null}
              </View>
            </View>
          </Reveal>

          <View style={{ marginBottom: spacing.lg }} />

          {/* The original screenshot — real context for the extraction, not
              the reason the save was opened, so it's a compact card rather
              than a near-full-screen image. See `SourcePreviewCard`. */}
          {isImageSave && save.thumbnailUrl ? (
            <Reveal index={2}>
              <SourcePreviewCard uri={save.thumbnailUrl} />
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

          {/* The generic answer to "what can I do with this" for any type
              with no bespoke act of its own — a real external link the
              content came from, promoted from the de-emphasised Source row
              to a proper primary button. Never shown for a screenshot: its
              `sourceUrl` is the screenshot's own Storage object, not an
              external link worth surfacing as an action. */}
          {showPrimaryOpenSource ? (
            <Reveal index={2}>
              <Touchable
                accessibilityRole="link"
                accessibilityLabel={`Open original on ${sourcePlatformName(save.sourceUrl as string)}`}
                onPress={() => void Linking.openURL(save.sourceUrl as string).catch(() => {})}
                haptic="medium"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: spacing.smd,
                  paddingVertical: spacing.md,
                  borderRadius: radius.sm,
                  backgroundColor: palette.accent,
                  marginBottom: spacing.smd,
                }}
              >
                <Glyph name="link" size={16} weight={2} color={palette.onAccent} />
                <AppText variant="label" style={{ color: palette.onAccent }}>
                  Open source ↗
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {/* Add to Space — organisation, not the reason this screen was
              opened, so it's a small pill rather than a full-width bar
              competing with the primary act above it. Still names the
              result once something is chosen ("Space: Kyoto Trip"), just
              without the explanatory caption a self-describing icon+label
              doesn't need. */}
          {save.status === 'ready' ? (
            <Reveal index={2}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={currentSpace ? `In ${currentSpace.name} — tap to change` : 'Add to a Space'}
                onPress={() => setShowSpaceSheet(true)}
                haptic="light"
                weight="tile"
                style={{
                  flexDirection: 'row',
                  alignSelf: 'flex-start',
                  alignItems: 'center',
                  gap: spacing.xs,
                  paddingVertical: spacing.xs + 2,
                  paddingHorizontal: spacing.smd,
                  borderRadius: 100,
                  borderWidth: 1,
                  borderColor: currentSpace ? spaceIdentity(currentSpace).color : palette.border,
                  backgroundColor: currentSpace ? `${spaceIdentity(currentSpace).color}14` : palette.surface,
                  marginBottom: spacing.smd,
                }}
              >
                <Glyph
                  name={currentSpace ? spaceIdentity(currentSpace).glyph : 'layers'}
                  size={14}
                  weight={2}
                  color={currentSpace ? spaceIdentity(currentSpace).color : palette.textMuted}
                />
                <AppText variant="bodySmall" tone={currentSpace ? 'default' : 'muted'}>
                  {currentSpace ? currentSpace.name : 'Add to Space'}
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {/* Progress sits right after the space it belongs to — "what do I
              want to do with it" then "where am I with it" — and before the
              extracted content, per the same reasoning point 4 states: this
              is a status the user checks often and shouldn't have to scroll
              past ingredients or exercises to reach. */}
          {save.status === 'ready' && save.knowledgeType && saveTypeMeta(save.knowledgeType).hasProgress ? (
            <Reveal index={2}>
              {/* No `onChange` reload: the strip writes through the store, so
                  `save.lifecycleStatus` above is already the new value on the
                  next render — re-fetching would only confirm what we wrote. */}
              <LifecycleStrip saveId={save.id} value={save.lifecycleStatus ?? 'saved'} />
            </Reveal>
          ) : null}

          {/* Summary — the reason a saved item is useful later, not secondary
              metadata, so it renders right after Progress and before every
              other extracted field. */}
          {model?.lede ? (
            <Reveal index={3}>
              <SectionLabel>Summary</SectionLabel>
              <AppText style={{ marginBottom: spacing.xl, lineHeight: 22 }}>{model.lede}</AppText>
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

          {/* Source — provenance, not a technical field. Plain text, no
              border or background: this is informational, not an action the
              rest of the screen's buttons are competing with, and the raw
              URL never needs to be on screen for the tap to work. The CTA text
              only repeats "Open source" when this row is the *only* way to
              open it — a bespoke act (recipe, place) already ate the primary
              button above, so here it's just an arrow. Never shown for a
              screenshot: its `sourceUrl` is the same Supabase Storage object
              already visible as the source-context card above, not a second,
              distinct provenance worth restating. */}
          {save.sourceUrl && !isImageSave ? (
            <Reveal index={4 + (displayFields.length ?? 1)}>
              <SectionLabel>Source</SectionLabel>
              <Touchable
                accessibilityRole="link"
                accessibilityLabel={`Open original on ${sourcePlatformName(save.sourceUrl)}`}
                haptic="medium"
                onPress={() => {
                  // Nothing to do if the OS has no handler — better a no-op
                  // than an unhandled rejection on a malformed stored URL.
                  void Linking.openURL(save.sourceUrl as string).catch(() => {});
                }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  paddingVertical: spacing.xs,
                  marginBottom: spacing.xl,
                }}
              >
                <AppText variant="bodySmall" tone="muted">
                  {sourcePlatformName(save.sourceUrl)}
                </AppText>
                <AppText variant="bodySmall" tone="accent">
                  {showPrimaryOpenSource ? '↗' : 'Open source ↗'}
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}

          {/* Renders nothing for a private save: a comment thread only you can
              see is a note to self, not a discussion. */}
          <Discussion saveId={save.id} spaceId={save.spaceId} />

          {/* Renders nothing when there is nothing similar enough — the
              server's distance cutoff, not a loading placeholder. */}
          {save.status === 'ready' ? <RelatedRail save={save} /> : null}
        </>
      ) : null}
    </Screen>
    {save && showSpaceSheet ? (
      <AddToSpaceSheet save={save} onClose={() => setShowSpaceSheet(false)} />
    ) : null}
    {save && showReminderSheet ? (
      <ReminderSheet
        save={save}
        onClose={() => {
          setShowReminderSheet(false);
          checkReminder();
        }}
      />
    ) : null}
    {cookModeOpen && recipeSteps.length > 0 ? (
      <CookMode steps={recipeSteps} onClose={() => setCookModeOpen(false)} />
    ) : null}
    </>
  );
}
