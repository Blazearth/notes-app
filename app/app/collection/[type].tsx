import { useLocalSearchParams } from 'expo-router';
import React from 'react';

import { CollectionDetailScreen } from '@/screens/CollectionDetailScreen';

export default function CollectionRoute() {
  const { type } = useLocalSearchParams<{ type: string }>();
  return <CollectionDetailScreen type={type} />;
}
