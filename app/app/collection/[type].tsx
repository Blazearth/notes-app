import { useLocalSearchParams } from 'expo-router';
import React from 'react';

import { CollectionDetailScreen } from '@/screens/CollectionDetailScreen';

/**
 * One route for every level of the collection tree.
 *
 * The param is still called `type` (renaming the file would break every
 * existing `router.push({ pathname: '/collection/[type]' })`), but it now
 * carries a full node id — `itinerary` for the type root, `itinerary~japan`
 * for one of its folders. A node id is a single path segment by construction,
 * since `slug` strips the `~` separator out of every facet value, so no
 * encoding or catch-all route is needed.
 */
export default function CollectionRoute() {
  const { type } = useLocalSearchParams<{ type: string }>();
  return <CollectionDetailScreen nodeId={type} />;
}
