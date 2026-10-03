import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, TextInput, View } from 'react-native';

import { ApiError } from '@/api/client';
import { track } from '@/analytics/client';
import { AnalyticsEvent } from '@/analytics/events';
import { repo } from '@/data';
import type { SaveResponse, SearchHit } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { getStore, KV } from '@/local';
import { buildCardModel } from '@/saves/cardModel';
import { mergeSearchHits, toLocalHits } from '@/search/merge';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * A handful of example queries that show what semantic search can do — not
 * drawn from any real library, so they never claim a specific result exists.
 * Tapping one runs a real search rather than previewing fake results.
 */
const SUGGESTED_QUERIES = [
  'romance anime without school',
  'places worth visiting in Japan',
  'workouts for back and biceps',
];

const RECENT_SEARCHES_LIMIT = 6;

async function loadRecentSearches(): Promise<string[]> {
  return (await getStore().readKv<string[]>(KV.recentSearches)) ?? [];
}

/** Most-recent-first, deduped case-insensitively, capped. Client-only — never synced. */
async function pushRecentSearch(query: string): Promise<string[]> {
  const store = getStore();
  const existing = (await store.readKv<string[]>(KV.recentSearches)) ?? [];
  const next = [query, ...existing.filter((q) => q.toLowerCase() !== query.toLowerCase())].slice(
    0,
    RECENT_SEARCHES_LIMIT,
  );
  await store.putKv(KV.recentSearches, next);
  return next;
}

/**
 * Which of the query's own words literally appear in what the card already
 * shows (title + meta) — real, derivable overlap, never a guess at *why* a
 * semantic match fired. There is no server signal for that, so nothing here
 * claims one: a `semantic`-only hit gets `SemanticBadge` and nothing more.
 */
function matchedWords(query: string, save: SaveResponse): string[] {
  const model = buildCardModel(save);
  const haystack = [model?.title, model?.meta]
    .filter((v): v is string => !!v)
    .join(' ')
    .toLowerCase();
  if (!haystack) return [];
  const words = query
    .toLowerCase()
    .split(/\s+/)
    .map((w) => w.replace(/[^\p{L}\p{N}]/gu, ''))
    .filter((w) => w.length > 2);
  return [...new Set(words.filter((w) => haystack.includes(w)))];
}

/**
 * `semantic`-only hits first, ahead of anything the query's own words also
 * touched — a two-stage read only when the split says something: an
 * all-semantic or all-direct result list renders flat instead.
 */
function splitHits(hits: SearchHit[]): { direct: SearchHit[]; related: SearchHit[]; showSplit: boolean } {
  const direct = hits.filter((h) => h.match !== 'semantic');
  const related = hits.filter((h) => h.match === 'semantic');
  return { direct, related, showSplit: direct.length > 0 && related.length > 0 };
}

/**
 * How long the field stays quiet before a *server* query goes out.
 *
 * Every server search costs an embedding call, so per-keystroke requests would
 * be both slow and wasteful. 350ms is roughly the gap between typing a word and
 * typing the next one.
 *
 * **The local half is not debounced at all.** It costs an FTS5 `MATCH` over the
 * device's own library — no network, no quota, nothing to be wasteful with — so
 * making it wait would be spending the one advantage it has.
 */
const DEBOUNCE_MS = 350;

/**
 * Search, local-first.
 *
 * Before L5 this screen was the last read path in the app that could only
 * answer from the network: an empty field, a spinner, and an error card with no
 * connection. Now the device's own index answers first and the server's hybrid
 * result merges in behind it — see `@/search/merge` for which wins where.
 *
 * The two halves are not interchangeable, which is why both run rather than one
 * being a fallback for the other. Local search cannot do semantics (embeddings
 * are `gemini-embedding-001` and there is no on-device model, deliberately — see
 * `docs/local-first.md`), and the server can find text the client never received
 * (`raw_caption` is indexed server-side and is not part of `SaveResponse`).
 */
type State =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | {
      kind: 'results';
      hits: SearchHit[];
      query: string;
      /** The server has not answered — still in flight, or it failed. */
      serverError: ApiError | null;
      serverPending: boolean;
    }
  | { kind: 'error'; error: ApiError };

/**
 * Marks a result the user's own words could not have found.
 *
 * Only shown for `semantic`. A `both` or `text` hit contains the words that
 * were typed, so it needs no explanation — but a purely semantic hit looks like
 * a mistake unless something says why it is there. Verified live: "somewhere
 * nice to eat in Denmark" returns a save whose text says Copenhagen and never
 * says Denmark.
 */
function SemanticBadge() {
  const { palette, radius, spacing } = useTheme();
  return (
    <View
      style={{
        paddingVertical: 3,
        paddingHorizontal: spacing.sm,
        borderRadius: radius.pill,
        backgroundColor: palette.surfaceVariant,
        borderWidth: 1,
        borderColor: palette.border,
      }}
    >
      <AppText variant="caption" tone="muted" style={{ fontSize: 9.5 }}>
        by meaning
      </AppText>
    </View>
  );
}

/** One result row: the card itself, plus — only for a hybrid hit with a real
 * literal overlap — the words that overlap. Never shown for a `semantic`-only
 * hit, which has no literal words to report. */
function ResultRow({
  hit,
  query,
  onPress,
}: {
  hit: SearchHit;
  query: string;
  onPress: () => void;
}) {
  const { spacing } = useTheme();
  const matched = hit.match === 'both' ? matchedWords(query, hit.save) : [];
  return (
    <View style={{ gap: 4 }}>
      <SaveCard save={hit.save} trailing={hit.match === 'semantic' ? <SemanticBadge /> : undefined} onPress={onPress} />
      {matched.length > 0 ? (
        <AppText variant="caption" tone="faint" style={{ marginLeft: spacing.smd }}>
          Matched: {matched.join(' · ')}
        </AppText>
      ) : null}
    </View>
  );
}

export function SearchScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const router = useRouter();

  const [query, setQuery] = useState('');
  const [state, setState] = useState<State>({ kind: 'idle' });
  const [recentSearches, setRecentSearches] = useState<string[]>([]);

  useEffect(() => {
    void loadRecentSearches().then(setRecentSearches);
  }, []);

  useEffect(() => {
    track(AnalyticsEvent.ScreenViewed, { screen_name: 'search' });
  }, []);

  /**
   * Guards against a slow early request landing after a fast later one and
   * overwriting it — the classic debounced-search race. Comparing the sequence
   * number is cheaper and more reliable than trying to abort the fetch. Both
   * halves share one ticket, so a stale *local* result cannot overwrite a fresh
   * merged one either.
   */
  const seq = useRef(0);
  /** The debounced server call not yet fired, so the search key can pre-empt it. */
  const pendingTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Refs, not state: the two halves land at different times and each needs to
  // merge against the other's *current* value. Two `useState`s would merge
  // against whatever the closure captured, which for the faster half is null.
  const localHits = useRef<SearchHit[]>([]);
  const serverHits = useRef<SearchHit[] | null>(null);

  /**
   * Renders whatever both halves have produced so far.
   *
   * The empty-list branches are the whole reason this is one function rather
   * than two `setState` calls: "nothing matched" and "still looking" are
   * indistinguishable from a hit count, and showing the first while the server
   * is still answering is the specific way a fast local index can make search
   * feel *worse*.
   */
  const publish = useCallback((trimmed: string, pending: boolean, error: ApiError | null) => {
    const hits = mergeSearchHits(localHits.current, serverHits.current);
    if (hits.length === 0) {
      if (pending) {
        setState({ kind: 'searching' });
        return;
      }
      // Nothing local, and the server could not be asked. That is an outage,
      // not an empty library, and saying "nothing matched" would be a lie.
      if (error !== null) {
        setState({ kind: 'error', error });
        return;
      }
    }
    setState({ kind: 'results', hits, query: trimmed, serverError: error, serverPending: pending });
  }, []);

  const runServer = useCallback(
    async (ticket: number, trimmed: string) => {
      if (!trimmed) return;
      // Recorded here, not on every keystroke: reaching the debounced server
      // call is what makes a query a real search rather than a word typed and
      // then changed.
      void pushRecentSearch(trimmed).then(setRecentSearches);
      try {
        const hits = await repo.searchSaves(trimmed);
        if (ticket !== seq.current) return;
        serverHits.current = hits;
        publish(trimmed, false, null);
        const merged = mergeSearchHits(localHits.current, hits);
        track(AnalyticsEvent.SearchPerformed, {
          result_count: merged.length,
          has_results: merged.length > 0,
        });
      } catch (e) {
        if (ticket !== seq.current) return;
        publish(
          trimmed,
          false,
          e instanceof ApiError ? e : new ApiError('server', 'Something went wrong', null),
        );
        track(AnalyticsEvent.SearchFailed, {
          error_type: e instanceof ApiError ? e.kind : 'unknown',
        });
      }
    },
    [publish],
  );

  useEffect(() => {
    const trimmed = query.trim();
    const ticket = ++seq.current;
    localHits.current = [];
    serverHits.current = null;

    if (!trimmed) {
      setState({ kind: 'idle' });
      return;
    }
    setState({ kind: 'searching' });

    // The local half runs at once and un-debounced — it is an index read on the
    // device, so there is nothing to be economical with, and waiting 350ms to
    // show results the app already holds would give away the only advantage it
    // has over the server.
    void getStore()
      .searchLocal(trimmed)
      .then(
        (saves) => {
          if (ticket !== seq.current) return;
          localHits.current = toLocalHits(saves);
          publish(trimmed, serverHits.current === null, null);
        },
        () => {
          // A local read that throws is a bug in the query, not an outage —
          // there is no offline case to surface here. The server half stands.
        },
      );

    pendingTimer.current = setTimeout(() => {
      pendingTimer.current = null;
      void runServer(ticket, trimmed);
    }, DEBOUNCE_MS);
    return () => {
      if (pendingTimer.current) clearTimeout(pendingTimer.current);
      pendingTimer.current = null;
    };
  }, [query, publish, runServer]);

  /**
   * Both the "try again" button and the keyboard's search key.
   *
   * Cancels the debounced call for the same query first — otherwise pressing
   * search inside the debounce window sent the request twice. And shows the
   * request is under way: "Try again" used to give no sign anything happened.
   */
  const searchNow = useCallback(() => {
    const trimmed = query.trim();
    if (!trimmed) return;
    if (pendingTimer.current) {
      clearTimeout(pendingTimer.current);
      pendingTimer.current = null;
    }
    serverHits.current = null;
    publish(trimmed, true, null);
    void runServer(seq.current, trimmed);
  }, [query, runServer, publish]);

  const offline = useMemo(
    () => state.kind === 'results' && state.serverError !== null,
    [state],
  );

  return (
    <Screen>
      <Reveal
        index={0}
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, marginBottom: spacing.lg }}
      >
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
          }}
        >
          <Glyph name="chevron" size={icon.sm} />
        </Touchable>

        <View
          style={{
            flex: 1,
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.smd,
            backgroundColor: palette.surface,
            borderWidth: 1,
            borderColor: palette.border,
            borderRadius: radius.pill,
            paddingVertical: spacing.sm,
            paddingHorizontal: spacing.lg,
          }}
        >
          <Glyph name="search" size={16} weight={2} />
          <TextInput
            value={query}
            onChangeText={setQuery}
            // The whole point of this screen; anything else is a tap away.
            autoFocus
            returnKeyType="search"
            onSubmitEditing={searchNow}
            placeholder="Search your library…"
            placeholderTextColor={palette.textFaint}
            accessibilityLabel="Search your saves"
            style={{
              flex: 1,
              paddingVertical: 6,
              fontSize: 15,
              color: palette.text,
            }}
          />
          {query ? (
            <Touchable
              accessibilityRole="button"
              accessibilityLabel="Clear search"
              onPress={() => setQuery('')}
              haptic="selection"
            >
              <AppText variant="caption" tone="muted">
                Clear
              </AppText>
            </Touchable>
          ) : null}
        </View>
      </Reveal>

      {/* Compact by design — this disappears the moment a keystroke lands
          (`query` becomes non-empty, which flips `state.kind` away from
          `idle` in the effect below), so it never competes with the keyboard
          for space. No `Card` chrome: this is orientation, not content. */}
      {state.kind === 'idle' ? (
        <Reveal index={1}>
          <View style={{ marginBottom: spacing.xl }}>
            <SectionLabel>Search by meaning</SectionLabel>
            <AppText variant="bodySmall" tone="muted" style={{ marginBottom: spacing.md }}>
              Search by words, or describe what you're looking for.
            </AppText>
            <View style={{ gap: 2 }}>
              {SUGGESTED_QUERIES.map((example) => (
                <Touchable
                  key={example}
                  accessibilityRole="button"
                  accessibilityLabel={`Search for ${example}`}
                  onPress={() => setQuery(example)}
                  haptic="selection"
                  style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm }}
                >
                  <Glyph name="search" size={13} weight={2} color={palette.textFaint} />
                  <AppText variant="bodySmall" tone="muted">
                    “{example}”
                  </AppText>
                </Touchable>
              ))}
            </View>
          </View>

          {recentSearches.length > 0 ? (
            <View>
              <SectionLabel>Recent searches</SectionLabel>
              <View style={{ gap: 2 }}>
                {recentSearches.map((q) => (
                  <Touchable
                    key={q}
                    accessibilityRole="button"
                    accessibilityLabel={`Search again for ${q}`}
                    onPress={() => setQuery(q)}
                    haptic="selection"
                    style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: spacing.sm }}
                  >
                    <Glyph name="clock" size={13} weight={2} color={palette.textFaint} />
                    <AppText variant="bodySmall">{q}</AppText>
                  </Touchable>
                ))}
              </View>
            </View>
          ) : null}
        </Reveal>
      ) : null}

      {state.kind === 'searching' ? (
        <View style={{ paddingVertical: spacing.xxl, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {state.kind === 'error' ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              {state.error.kind === 'network' ? "Can't reach Weavr" : 'Search failed'}
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              {state.error.message}
            </AppText>
            <Touchable accessibilityRole="button" onPress={searchNow} haptic="medium">
              <AppText variant="label" tone="accent">
                Try again
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}

      {state.kind === 'results' && state.hits.length === 0 ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Nothing matched “{state.query}”
            </AppText>
            {/* Only `ready` saves are searchable — anything still in the
                pipeline has not been classified or embedded yet, and saying so
                is more useful than implying the library is empty. */}
            <AppText variant="caption" tone="muted">
              Try fewer or different words. Saves that are still processing cannot be searched yet.
            </AppText>
          </Card>
        </Reveal>
      ) : null}

      {/* Local results are already on screen and the server has not answered
          yet. A slim line rather than the full spinner: the list below is real,
          and replacing it with a spinner would be undoing the point. */}
      {state.kind === 'results' && state.serverPending && state.hits.length > 0 ? (
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: spacing.sm,
            marginBottom: spacing.smd,
          }}
        >
          <ActivityIndicator size="small" color={palette.accent} />
          <AppText variant="caption" tone="muted">
            Looking for related saves…
          </AppText>
        </View>
      ) : null}

      {/* Local results stand, and the reason they are all there is is said out
          loud. Without this the list looks like a complete answer that happens
          to be missing the semantic hits — worse than a slower, whole one. */}
      {offline && state.kind === 'results' && state.hits.length > 0 ? (
        <Reveal index={1}>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing.sm,
              marginBottom: spacing.smd,
            }}
          >
            <Glyph name="search" size={13} weight={2} />
            <AppText variant="caption" tone="muted" style={{ flex: 1 }}>
              Showing matches found on this device. Weavr could not be reached, so results that
              only match by meaning are missing.
            </AppText>
            <Touchable accessibilityRole="button" accessibilityLabel="Retry search" onPress={searchNow}>
              <AppText variant="label" tone="accent">
                Retry
              </AppText>
            </Touchable>
          </View>
        </Reveal>
      ) : null}

      {state.kind === 'results' && state.hits.length > 0
        ? (() => {
            const { direct, related, showSplit } = splitHits(state.hits);
            const goTo = (id: string, position: number, knowledgeType: string | undefined) => () => {
              track(AnalyticsEvent.SearchResultOpened, {
                position,
                knowledge_type: knowledgeType ?? 'unknown',
              });
              router.push({ pathname: '/save/[id]', params: { id } });
            };
            return (
              <View style={{ gap: spacing.smd }}>
                <AppText variant="caption" tone="faint">
                  {state.hits.length} {state.hits.length === 1 ? 'result' : 'results'}
                </AppText>
                {direct.map((hit, i) => (
                  <Reveal key={hit.save.id} index={i}>
                    <ResultRow
                      hit={hit}
                      query={state.query}
                      onPress={goTo(hit.save.id, i, hit.save.knowledgeType)}
                    />
                  </Reveal>
                ))}
                {/* Only when the split says something: a query that returned
                    nothing but semantic hits (or nothing but literal ones)
                    renders as one flat list instead of a section with nothing
                    above or below it. */}
                {showSplit ? (
                  <>
                    <SectionLabel>Also related</SectionLabel>
                    {related.map((hit, i) => (
                      <Reveal key={hit.save.id} index={direct.length + i}>
                        <ResultRow
                          hit={hit}
                          query={state.query}
                          onPress={goTo(hit.save.id, direct.length + i, hit.save.knowledgeType)}
                        />
                      </Reveal>
                    ))}
                  </>
                ) : null}
              </View>
            );
          })()
        : null}
    </Screen>
  );
}
