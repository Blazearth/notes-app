import { useLocalSearchParams } from 'expo-router';
import React from 'react';

import { WorkoutCompareScreen } from '@/screens/WorkoutCompareScreen';

/** `ids` is a comma-separated list of save ids — no route param array support needed for a handful of selections. */
export default function CompareWorkoutsRoute() {
  const { ids } = useLocalSearchParams<{ ids: string }>();
  return <WorkoutCompareScreen ids={ids ? ids.split(',').filter(Boolean) : []} />;
}
