/**
 * The mockups draw every icon out of primitives — rings, squares, rotated
 * diamonds, a plus made of two bars — rather than an icon font. Reproducing
 * them as `View`s keeps the implementation faithful and drops an entire
 * dependency (and its font-loading race) from the app.
 */

import React from 'react';
import { View, type ViewStyle } from 'react-native';

import { useTheme } from '@/theme/ThemeProvider';

export type GlyphName =
  | 'search'
  | 'plus'
  | 'circle'
  | 'ring'
  | 'square'
  | 'roundedSquare'
  | 'diamond'
  | 'chevron'
  | 'bars'
  | 'tray'
  | 'capsule'
  | 'arch'
  | 'page'
  | 'sliders';

export interface GlyphProps {
  name: GlyphName;
  size?: number;
  color?: string;
  /** Stroke width for outlined shapes. */
  weight?: number;
  style?: ViewStyle;
}

export function Glyph({ name, size = 18, color, weight = 2, style }: GlyphProps) {
  const { palette } = useTheme();
  const stroke = color ?? palette.textMuted;
  const box: ViewStyle = { width: size, height: size, alignItems: 'center', justifyContent: 'center' };

  switch (name) {
    case 'search':
    case 'ring':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size,
              height: size,
              borderRadius: size / 2,
              borderWidth: weight,
              borderColor: stroke,
            }}
          />
        </View>
      );

    case 'circle':
      return (
        <View style={[box, style]}>
          <View style={{ width: size, height: size, borderRadius: size / 2, backgroundColor: stroke }} />
        </View>
      );

    case 'plus':
      return (
        <View style={[box, style]}>
          <View style={{ width: size, height: weight, backgroundColor: stroke, borderRadius: weight }} />
          <View
            style={{
              position: 'absolute',
              width: weight,
              height: size,
              backgroundColor: stroke,
              borderRadius: weight,
            }}
          />
        </View>
      );

    case 'square':
      return (
        <View style={[box, style]}>
          <View style={{ width: size, height: size, borderWidth: weight, borderColor: stroke }} />
        </View>
      );

    case 'roundedSquare':
      return (
        <View style={[box, style]}>
          <View
            style={{ width: size, height: size, borderRadius: 5, borderWidth: weight, borderColor: stroke }}
          />
        </View>
      );

    case 'diamond':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size * 0.72,
              height: size * 0.72,
              borderWidth: weight,
              borderColor: stroke,
              borderRadius: 2,
              transform: [{ rotate: '45deg' }],
            }}
          />
        </View>
      );

    // Open-bottom bracket — the mockups' "filter / sort" affordance.
    case 'chevron':
    case 'tray':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size,
              height: size * 0.72,
              borderLeftWidth: weight,
              borderRightWidth: weight,
              borderBottomWidth: weight,
              borderColor: stroke,
            }}
          />
        </View>
      );

    // Landscape rectangle — "scan document".
    case 'bars':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size,
              height: size * 0.8,
              borderWidth: weight,
              borderColor: stroke,
              borderRadius: 3,
            }}
          />
        </View>
      );

    // Tall pill — "voice note".
    case 'capsule':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size * 0.6,
              height: size,
              borderRadius: size / 2,
              borderWidth: weight,
              borderColor: stroke,
            }}
          />
        </View>
      );

    // Top-only rounded box — "upload file".
    case 'arch':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size,
              height: size,
              borderTopWidth: weight,
              borderLeftWidth: weight,
              borderRightWidth: weight,
              borderColor: stroke,
              borderTopLeftRadius: 4,
              borderTopRightRadius: 4,
            }}
          />
        </View>
      );

    // Portrait rectangle — "text note".
    case 'page':
      return (
        <View style={[box, style]}>
          <View
            style={{
              width: size * 0.8,
              height: size,
              borderWidth: weight,
              borderColor: stroke,
              borderRadius: 2,
            }}
          />
        </View>
      );

    case 'sliders':
      return (
        <View style={[box, style]}>
          <View style={{ width: size, height: weight, backgroundColor: stroke, marginBottom: 4 }} />
          <View style={{ width: size * 0.6, height: weight, backgroundColor: stroke }} />
        </View>
      );
  }
}
