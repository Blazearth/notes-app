import { useLocalSearchParams } from 'expo-router';
import React from 'react';

import { WorkoutSessionScreen } from '@/screens/WorkoutSessionScreen';

/**
 * Running a collection node's merged exercises — reached from "Start workout"
 * on a training split. Its own route rather than a modal on the collection
 * screen so the back gesture ends the session cleanly and the collection is
 * still there underneath.
 */
export default function SessionRoute() {
  const { nodeId } = useLocalSearchParams<{ nodeId: string }>();
  return <WorkoutSessionScreen nodeId={nodeId} />;
}
