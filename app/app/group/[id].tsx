import { useLocalSearchParams } from 'expo-router';
import React from 'react';

import { GroupDetailScreen } from '@/screens/GroupDetailScreen';

/**
 * One route for every level of the hierarchy.
 *
 * A subgroup pushes this same route with its own id, so the navigation stack
 * *is* the breadcrumb trail and back always goes up exactly one level — no
 * depth tracking, and nothing to change when groups nest deeper.
 */
export default function GroupRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  return <GroupDetailScreen id={id} />;
}
