/**
 * Real stroke icons — ported path-for-path from the Claude Design mockup's
 * inline SVGs (Feather-style: 24×24 viewBox, round caps/joins, fill none).
 * A handful of purely decorative shapes (selection dots, placeholder fills)
 * don't correspond to anything in the mockup and stay simple `View`
 * primitives rather than being forced into an SVG they were never drawn as.
 */
import React from 'react';
import { View, type ViewStyle } from 'react-native';
import Svg, { Circle, Line, Path, Rect } from 'react-native-svg';

import { useTheme } from '@/theme/ThemeProvider';

export type GlyphName =
  // Real icons (SVG paths from the mockup)
  | 'search'
  | 'plus'
  | 'chevron'
  | 'filter'
  | 'layers'
  | 'settings'
  | 'link'
  | 'fileText'
  | 'camera'
  | 'corners'
  | 'mic'
  | 'upload'
  | 'textNote'
  | 'download'
  // Decorative primitives — not drawn as icons in the mockup
  | 'circle'
  | 'ring'
  | 'square'
  | 'roundedSquare'
  | 'diamond';

export interface GlyphProps {
  name: GlyphName;
  size?: number;
  color?: string;
  /** Stroke width for outlined shapes/icons. */
  weight?: number;
  style?: ViewStyle;
}

const PRIMITIVES: ReadonlySet<GlyphName> = new Set([
  'circle',
  'ring',
  'square',
  'roundedSquare',
  'diamond',
]);

function StrokeIcon({
  size,
  color,
  weight,
  style,
  children,
}: {
  size: number;
  color: string;
  weight: number;
  style?: ViewStyle;
  children: React.ReactNode;
}) {
  return (
    <Svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth={weight}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={style}
    >
      {children}
    </Svg>
  );
}

export function Glyph({ name, size = 18, color, weight = 2, style }: GlyphProps) {
  const { palette } = useTheme();
  const stroke = color ?? palette.textMuted;

  if (!PRIMITIVES.has(name)) {
    switch (name) {
      case 'search':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Circle cx="11" cy="11" r="7" />
            <Path d="m21 21-4.3-4.3" />
          </StrokeIcon>
        );

      case 'plus':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Line x1="12" y1="5" x2="12" y2="19" />
            <Line x1="5" y1="12" x2="19" y2="12" />
          </StrokeIcon>
        );

      // Back-navigation chevron.
      case 'chevron':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="m15 18-6-6 6-6" />
          </StrokeIcon>
        );

      // Filter / sort — the Library header's second action.
      case 'filter':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M3 5h18M6 12h12M10 19h4" />
          </StrokeIcon>
        );

      // Spaces breadcrumb.
      case 'layers':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="m12 2 9 5-9 5-9-5 9-5Z" />
            <Path d="m3 12 9 5 9-5" />
            <Path d="m3 17 9 5 9-5" />
          </StrokeIcon>
        );

      case 'settings':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Circle cx="12" cy="12" r="3" />
            <Path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </StrokeIcon>
        );

      // Paste Link.
      case 'link':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M9 17H7a5 5 0 0 1 0-10h2" />
            <Path d="M15 7h2a5 5 0 1 1 0 10h-2" />
            <Line x1="8" y1="12" x2="16" y2="12" />
          </StrokeIcon>
        );

      // Scan Doc.
      case 'fileText':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
            <Path d="M14 2v6h6" />
            <Line x1="9" y1="13" x2="15" y2="13" />
            <Line x1="9" y1="17" x2="13" y2="17" />
          </StrokeIcon>
        );

      case 'camera':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3Z" />
            <Circle cx="12" cy="13" r="3.5" />
          </StrokeIcon>
        );

      // Screenshot.
      case 'corners':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M15 3h4a2 2 0 0 1 2 2v4M9 21H5a2 2 0 0 1-2-2v-4M21 15v4a2 2 0 0 1-2 2h-4M3 9V5a2 2 0 0 1 2-2h4" />
          </StrokeIcon>
        );

      // Voice Note.
      case 'mic':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Rect x="9" y="2" width="6" height="12" rx="3" />
            <Path d="M5 10a7 7 0 0 0 14 0M12 19v3" />
          </StrokeIcon>
        );

      // Upload File.
      case 'upload':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M12 16V4M6 10l6-6 6 6" />
            <Path d="M4 20h16" />
          </StrokeIcon>
        );

      // Text Note.
      case 'textNote':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M4 7V4h16v3" />
            <Line x1="9" y1="20" x2="15" y2="20" />
            <Line x1="12" y1="4" x2="12" y2="20" />
          </StrokeIcon>
        );

      // Import.
      case 'download':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M12 3v12M7 10l5 5 5-5" />
            <Path d="M5 21h14" />
          </StrokeIcon>
        );
    }
  }

  const box: ViewStyle = { width: size, height: size, alignItems: 'center', justifyContent: 'center' };

  switch (name) {
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

    // Selection/placeholder bullet — not a mockup icon.
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

    default:
      return null;
  }
}
