import React from 'react';

import { MorphPresentation } from '@/motion/MorphPresentation';
import { SettingsScreen } from '@/screens/SettingsScreen';

/**
 * Settings is presented as a container transform rather than a page push: the
 * surface grows out of the gear on Home and collapses back into it.
 *
 * The route's stack options (`transparentModal`, `animation: 'none'`) are what
 * hand the transition over — see `app/_layout.tsx`. The screen underneath stays
 * mounted and visible, which is the whole premise: there is no second page, only
 * this one opening on top of the first.
 */
export default function SettingsRoute() {
  return (
    <MorphPresentation sourceGlyph="settings">
      <SettingsScreen />
    </MorphPresentation>
  );
}
