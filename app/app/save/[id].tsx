import { Redirect, useLocalSearchParams } from 'expo-router';
import React from 'react';

import { SaveDetailScreen } from '@/screens/SaveDetailScreen';

export default function SaveDetailRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();

  // A deep link with no id is reachable from outside the app, so it has to be
  // handled rather than passed on as `undefined` and 404'd by the API.
  if (!id) return <Redirect href="/" />;

  return <SaveDetailScreen id={id} />;
}
