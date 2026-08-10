import { Redirect, useLocalSearchParams } from 'expo-router';
import React from 'react';

import { SpaceDetailScreen } from '@/screens/SpaceDetailScreen';

export default function SpaceDetailRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();

  // Same reasoning as the save route: a deep link with no id is reachable from
  // outside the app, so it is handled here rather than passed on as
  // `undefined` and 404'd by the API. Home, not `/spaces` — Spaces is a tab
  // inside the shell, not a route of its own.
  if (!id) return <Redirect href="/" />;

  return <SpaceDetailScreen spaceId={id} />;
}
