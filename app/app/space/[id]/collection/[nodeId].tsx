import { Redirect, useLocalSearchParams } from 'expo-router';
import React from 'react';

import { SpaceCollectionScreen } from '@/screens/SpaceCollectionScreen';

/**
 * A Space's own view of one collection node — S1/S2 of
 * `docs/knowledge-spaces.md`.
 *
 * Nested under the Space rather than reusing `/collection/[nodeId]`, because
 * the two answer different questions with the same node id: that one renders
 * the viewer's whole library, this one only what the Space holds. Nesting is
 * also what makes back go to the Space rather than to the Library.
 *
 * The route pushes onto itself for a child folder, so the navigation stack is
 * the breadcrumb trail — the same property `group/[id]` has.
 */
export default function SpaceCollectionRoute() {
  const { id, nodeId } = useLocalSearchParams<{ id: string; nodeId: string }>();

  if (!id || !nodeId) return <Redirect href="/" />;

  return <SpaceCollectionScreen spaceId={id} nodeId={nodeId} />;
}
