import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, TextInput, View } from 'react-native';

import { ApiError, searchSaves } from '@/api/client';
import type { SearchHit } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { SaveCard } from '@/components/SaveCard';
import { Screen } from '@/components/Screen';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * How long the field stays quiet before a query goes out.
 *
 * Every search costs an embedding call server-side, so per-keystroke requests
 * would be both slow and wasteful. 350ms is roughly the gap between typing a
 * word and typing the next one.
 */
const DEBOUNCE_MS = 350;

type State =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | { kind: 'results'; hits: SearchHit[]; query: string }
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
        related
      </AppText>
    </View>
  );
}

export function SearchScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const router = useRouter();

  const [query, setQuery] = useState('');
  const [state, setState] = useState<State>({ kind: 'idle' });

  /**
   * Guards against a slow early request landing after a fast later one and
   * overwriting it — the classic debounced-search race. Comparing the sequence
   * number is cheaper and more reliable than trying to abort the fetch.
   */
  const seq = useRef(0);

  const run = useCallback(async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) {
      setState({ kind: 'idle' });
      return;
    }

    const mine = ++seq.current;
    setState({ kind: 'searching' });

    try {
      const hits = await searchSaves(trimmed);
      if (mine !== seq.current) return;
      setState({ kind: 'results', hits, query: trimmed });
    } catch (e) {
      if (mine !== seq.current) return;
      setState({
        kind: 'error',
        error: e instanceof ApiError ? e : new ApiError('server', 'Something went wrong', null),
      });
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => void run(query), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [query, run]);

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
            onSubmitEditing={() => void run(query)}
            placeholder="Ask or find anything…"
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

      {state.kind === 'idle' ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Search everything you have saved
            </AppText>
            <AppText variant="caption" tone="muted">
              Exact words work, and so does describing what you are after — "somewhere nice to eat
              in Copenhagen" finds the place even if you never wrote those words down.
            </AppText>
          </Card>
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
            <Touchable accessibilityRole="button" onPress={() => void run(query)} haptic="medium">
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

      {state.kind === 'results' && state.hits.length > 0 ? (
        <View style={{ gap: spacing.smd }}>
          {state.hits.map((hit, i) => (
            <Reveal key={hit.save.id} index={i}>
              <SaveCard
                save={hit.save}
                trailing={hit.match === 'semantic' ? <SemanticBadge /> : undefined}
                onPress={() =>
                  router.push({ pathname: '/save/[id]', params: { id: hit.save.id } })
                }
              />
            </Reveal>
          ))}
        </View>
      ) : null}
    </Screen>
  );
}
