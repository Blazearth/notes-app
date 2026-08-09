import { useRouter } from 'expo-router';
import React, { useCallback, useEffect } from 'react';
import { ActivityIndicator, RefreshControl, View } from 'react-native';

import type { ShoppingListItem, ShoppingListResponse } from '@/api/types';
import { useLiveValue } from '@/local';
import { writeClearCheckedShoppingItems, writeShoppingItemChecked } from '@/local/writes';
import { sync } from '@/local/sync';
import { useTaskStatus } from '@/local/useSync';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { Glyph } from '@/components/Glyph';
import { Reveal } from '@/components/Reveal';
import { Screen } from '@/components/Screen';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { useTheme } from '@/theme/ThemeProvider';

/** Stable identity for the "store has nothing yet" case — see `useLiveValue`. */
const EMPTY_LIST: ShoppingListResponse = { items: [], categories: [] };

function ItemRow({
  item,
  onToggle,
}: {
  item: ShoppingListItem;
  onToggle: (item: ShoppingListItem) => void;
}) {
  const { palette, radius, spacing } = useTheme();
  const quantity = [item.quantity, item.unit].filter(Boolean).join(' ');

  return (
    <Touchable
      accessibilityRole="checkbox"
      accessibilityState={{ checked: item.checked }}
      accessibilityLabel={`${item.name}${quantity ? `, ${quantity}` : ''}`}
      onPress={() => onToggle(item)}
      haptic="selection"
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: spacing.md,
        paddingVertical: spacing.smd,
      }}
    >
      <View
        style={{
          width: 22,
          height: 22,
          borderRadius: radius.sm,
          borderWidth: 1.5,
          borderColor: item.checked ? palette.accent : palette.border,
          backgroundColor: item.checked ? palette.accent : 'transparent',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {item.checked ? <Glyph name="chevron" size={12} weight={2.5} color={palette.onAccent} /> : null}
      </View>

      <AppText
        style={{
          flex: 1,
          // Struck through rather than removed: seeing what you have already
          // picked up is half of what a shopping list is for.
          textDecorationLine: item.checked ? 'line-through' : 'none',
          color: item.checked ? palette.textFaint : palette.text,
        }}
        numberOfLines={2}
      >
        {item.name}
      </AppText>

      {quantity ? (
        <AppText variant="bodySmall" tone="muted" style={{ opacity: item.checked ? 0.5 : 1 }}>
          {quantity}
        </AppText>
      ) : null}

      {/* Two or more recipes wanted this. Worth surfacing: it explains a
          quantity larger than any single recipe called for. */}
      {item.sources.length > 1 ? (
        <View
          style={{
            paddingVertical: 2,
            paddingHorizontal: spacing.xs + 2,
            borderRadius: radius.pill,
            backgroundColor: palette.surfaceVariant,
            borderWidth: 1,
            borderColor: palette.border,
          }}
        >
          <AppText variant="caption" tone="muted" style={{ fontSize: 9.5 }}>
            ×{item.sources.length}
          </AppText>
        </View>
      ) : null}
    </Touchable>
  );
}

export function ShoppingListScreen() {
  const { palette, radius, spacing, icon } = useTheme();
  const router = useRouter();

  // Reads the store, so the list is on screen before the network is asked —
  // which matters more here than anywhere else in the app: this is the one
  // screen used standing in a shop, on the worst connection the app ever sees.
  const list = useLiveValue<ShoppingListResponse>(
    ['shopping_items', 'kv'],
    (store) => store.readShoppingList(),
    EMPTY_LIST,
  );
  const items = list.items;
  const categories = list.categories;

  const task = useTaskStatus('shoppingList', items.length > 0);
  const status: 'loading' | 'ready' | 'error' =
    items.length > 0 ? 'ready' : task.error ? 'error' : task.firstLoad ? 'loading' : 'ready';
  const error = task.error;

  useEffect(() => {
    void sync.syncShoppingList();
  }, []);

  /**
   * The interaction this whole layer exists for: repeated taps, standing in a
   * shop, on the worst connection the app ever sees.
   *
   * The rollback that used to be here is gone, and its absence is the
   * improvement. A tick undone by a failed request looks exactly like a tap the
   * app never registered — so you tap again, and again. The tick now stands, the
   * request is retried until it lands, and the only thing that can undo it is
   * the server actually rejecting it.
   */
  const toggle = useCallback((item: ShoppingListItem) => {
    writeShoppingItemChecked(item.id, !item.checked);
  }, []);

  const clearChecked = useCallback(() => {
    writeClearCheckedShoppingItems(items);
  }, [items]);

  const checkedCount = items.filter((i) => i.checked).length;
  // Server order is already aisle-then-name, so grouping only has to preserve
  // it — never re-sort here, or the shopper walks the shop twice.
  const present = categories.filter((c) => items.some((i) => i.category === c));

  return (
    <Screen
      refreshControl={
        <RefreshControl
          refreshing={task.running}
          onRefresh={() => void sync.syncShoppingList()}
          tintColor={palette.accent}
          colors={[palette.accent]}
          progressBackgroundColor={palette.surface}
        />
      }
    >
      <Reveal
        index={0}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: spacing.lg,
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd }}>
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
          <AppText variant="display">Shopping</AppText>
        </View>
      </Reveal>

      {status === 'loading' ? (
        <View style={{ paddingVertical: spacing.xxl * 2, alignItems: 'center' }}>
          <ActivityIndicator color={palette.accent} />
        </View>
      ) : null}

      {status === 'error' ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              {error?.kind === 'network' ? "Can't reach Weavr" : 'Could not load your list'}
            </AppText>
            <AppText variant="caption" tone="muted" style={{ marginBottom: spacing.md }}>
              {error?.message}
            </AppText>
            <Touchable accessibilityRole="button" onPress={() => void sync.syncShoppingList()} haptic="medium">
              <AppText variant="label" tone="accent">
                Try again
              </AppText>
            </Touchable>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && items.length === 0 ? (
        <Reveal index={1}>
          <Card>
            <AppText variant="cardTitle" style={{ marginBottom: spacing.xs }}>
              Nothing to buy yet
            </AppText>
            <AppText variant="caption" tone="muted">
              Open a saved recipe and tap “Add to shopping list”. Ingredients from several recipes
              are combined into one list.
            </AppText>
          </Card>
        </Reveal>
      ) : null}

      {status === 'ready' && items.length > 0 ? (
        <>
          {present.map((category, index) => (
            <Reveal key={category} index={1 + index}>
              <SectionLabel>{category}</SectionLabel>
              <View style={{ marginBottom: spacing.lg }}>
                {items
                  .filter((i) => i.category === category)
                  .map((item) => (
                    <ItemRow key={item.id} item={item} onToggle={toggle} />
                  ))}
              </View>
            </Reveal>
          ))}

          {checkedCount > 0 ? (
            <Reveal index={1 + present.length}>
              <Touchable
                accessibilityRole="button"
                accessibilityLabel={`Clear ${checkedCount} checked items`}
                onPress={clearChecked}
                haptic="medium"
                style={{
                  alignItems: 'center',
                  paddingVertical: spacing.md,
                  borderRadius: radius.md,
                  borderWidth: 1,
                  borderColor: palette.border,
                  backgroundColor: palette.surface,
                }}
              >
                <AppText variant="label" tone="accent">
                  Clear {checkedCount} checked
                </AppText>
              </Touchable>
            </Reveal>
          ) : null}
        </>
      ) : null}
    </Screen>
  );
}
