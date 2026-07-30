import React from 'react';
import { ScrollView, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

/**
 * Shown when `EXPO_PUBLIC_*` configuration is missing.
 *
 * Deliberately styled with literal values rather than the theme: this renders
 * before the providers, and it has to work when the app is otherwise unable to
 * start. It is the only screen in the app that does not use `useTheme`.
 */
export function ConfigErrorScreen({ missing }: { missing: string[] }) {
  const insets = useSafeAreaInsets();

  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: '#0F0D09' }}
      contentContainerStyle={{ padding: 24, paddingTop: insets.top + 48, gap: 16 }}
    >
      <Text style={{ color: '#EDEBE7', fontSize: 22, fontWeight: '700' }}>
        Weavr is not configured
      </Text>

      <Text style={{ color: '#A8A49F', fontSize: 14, lineHeight: 20 }}>
        These variables are missing from <Text style={{ color: '#EDEBE7' }}>app/.env</Text>:
      </Text>

      <View
        style={{
          backgroundColor: '#1A1814',
          borderRadius: 12,
          borderWidth: 1,
          borderColor: '#35322E',
          padding: 16,
          gap: 6,
        }}
      >
        {missing.map((name) => (
          <Text key={name} style={{ color: '#8D9DF5', fontSize: 13 }}>
            {name}
          </Text>
        ))}
      </View>

      <Text style={{ color: '#A8A49F', fontSize: 14, lineHeight: 20 }}>
        Copy <Text style={{ color: '#EDEBE7' }}>app/.env.example</Text> to{' '}
        <Text style={{ color: '#EDEBE7' }}>app/.env</Text> and fill it in, then restart the bundler
        with <Text style={{ color: '#EDEBE7' }}>npx expo start --clear</Text>.
      </Text>

      <Text style={{ color: '#6C6864', fontSize: 13, lineHeight: 19 }}>
        EXPO_PUBLIC_* values are read at build time, so a bundler that is already running will not
        pick up the change.
      </Text>
    </ScrollView>
  );
}
