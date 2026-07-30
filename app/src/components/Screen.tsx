import React from 'react';
import { ScrollView, View, type StyleProp, type ViewStyle } from 'react-native';
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
}

export function Screen({
  children,
  cover = false,
  reserveNavSpace = true,
  contentStyle,
  scroll = true,
}: ScreenProps) {
  const { palette, layout, navBarStyle } = useTheme();
  const insets = useSafeAreaInsets();

  const bottomInset = reserveNavSpace
    ? navBarStyle === 'floating'
      ? layout.navScrollInset
      : layout.navScrollInset + insets.bottom
    : insets.bottom;

  const padding: StyleProp<ViewStyle> = [
    {
      paddingHorizontal: layout.screenGutter,
      paddingTop: insets.top + layout.screenGutter,
      paddingBottom: bottomInset,
    },
    contentStyle,
  ];

  return (
    <View style={{ flex: 1, backgroundColor: palette.background }}>
      {cover ? <CoverBanner /> : null}
      {scroll ? (
        <ScrollView
          contentContainerStyle={padding}
          showsVerticalScrollIndicator={false}
          // The mockups hide scrollbars entirely (`::-webkit-scrollbar`).
          contentInsetAdjustmentBehavior="never"
        >
          {children}
        </ScrollView>
      ) : (
        <View style={[{ flex: 1 }, padding]}>{children}</View>
      )}
    </View>
  );
}
