import { makeMutable } from 'react-native-reanimated';

/**
 * Fractional active-tab index shared between the tab shell and BottomNav.
 *
 *   0.0 = Home
 *   1.0 = Library
 *   2.0 = Spaces
 *
 * Fractional during a swipe (e.g. 0.5 = halfway between Home and Library).
 * Updated on the UI thread by the pan gesture and by selectTab, so BottomNav
 * can read it directly in useAnimatedStyle without a JS round-trip.
 */
export const activeTabFraction = makeMutable(0);
