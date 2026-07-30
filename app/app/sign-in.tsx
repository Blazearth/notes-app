import { Redirect } from 'expo-router';
import React from 'react';

import { useSession } from '@/auth/SessionProvider';
import { SignInScreen } from '@/screens/SignInScreen';

export default function SignInRoute() {
  const { session } = useSession();

  // Declarative guard rather than an imperative redirect in an effect — this
  // cannot race the first render, which is what makes sign-in flash.
  if (session) return <Redirect href="/" />;

  return <SignInScreen />;
}
