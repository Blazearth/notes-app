import React from 'react';
import {
  ScrollView,
  View,
  type RefreshControlProps,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { useTheme } from '@/theme/ThemeProvider';
import { CoverBanner } from './CoverBanner';

export interface ScreenProps {
  children: React.ReactNode;
  /** Render the personalised gradient cover behind the header. Home only. */
  cover?: boolean;
  /** Leave room at the bottom for the floating nav. */
  reserveNavSpace?: boolean;
  contentStyle?: StyleProp<ViewStyle>;
  scroll?: boolean;
  /** Pass a `<RefreshControl>` to enable pull-to-refresh. */
  refreshControl?: React.ReactElement<RefreshControlProps>;
}

/**
 * The padding/inset math `Screen` applies to its scroll container — pulled
 * out so a screen that needs a virtualized root (FlashList, which cannot sit
 * inside `Screen`'s own ScrollView) can still match its content inset
 * exactly instead of re-deriving it.
 */
export function useScreenContentStyle(reserveNavSpace = true): { paddingHorizontal: number; paddingTop: number; paddingBottom: number } {
  const { layout, navBarStyle } = useTheme();
  const insets = useSafeAreaInsets();

  const bottomInset = reserveNavSpace
    ? navBarStyle === 'floating'
      ? layout.navScrollInset
      : layout.navScrollInset + insets.bottom
    : insets.bottom;

  return {
    paddingHorizontal: layout.screenGutter,
    paddingTop: insets.top + layout.screenGutter,
    paddingBottom: bottomInset,
  };
}

export function Screen({
  children,
  cover = false,
  reserveNavSpace = true,
  contentStyle,
  scroll = true,
  refreshControl,
}: ScreenProps) {
  const { palette } = useTheme();
  const screenPadding = useScreenContentStyle(reserveNavSpace);

  const padding: StyleProp<ViewStyle> = [screenPadding, contentStyle];

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      {cover ? <CoverBanner /> : null}
      {scroll ? (
        <ScrollView
          contentContainerStyle={padding}
          showsVerticalScrollIndicator={false}
          // The mockups hide scrollbars entirely (`::-webkit-scrollbar`).
          contentInsetAdjustmentBehavior="never"
          refreshControl={refreshControl}
        >
          {children}
        </ScrollView>
      ) : (
        <View style={[{ flex: 1 }, padding]}>{children}</View>
      )}
    </View>
  );
}
