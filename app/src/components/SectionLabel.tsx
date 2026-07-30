import React from 'react';
import { View } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';

export function SectionLabel({ children, trailing }: { children: string; trailing?: React.ReactNode }) {
  const { spacing } = useTheme();
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: spacing.smd,
      }}
    >
      <AppText variant="sectionLabel">{children}</AppText>
      {trailing}
    </View>
  );
}
