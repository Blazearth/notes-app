import { Redirect, useLocalSearchParams } from 'expo-router';
import React from 'react';

import { ShoppingListScreen } from '@/screens/ShoppingListScreen';

/**
 * A Space's shared shopping list — S4 of `docs/knowledge-spaces.md`.
 *
 * Nested under the Space for the same reason `collection/[nodeId]` is: these
 * are two lists, not two views of one, and nesting is what makes back go to the
 * Space rather than to Home. The screen is the personal one with a scope, since
 * everything about how a shopping list *reads* is identical — only where the
 * items come from differs.
 */
export default function SpaceShoppingListRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();

  if (!id) return <Redirect href="/" />;

  return <ShoppingListScreen spaceId={id} />;
}
