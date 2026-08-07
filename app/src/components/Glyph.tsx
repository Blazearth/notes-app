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
  // Spaces redesign — not from the original mockup, ported Feather-style.
  | 'members'
  | 'clock'
  | 'compass'
  | 'book'
  | 'briefcase'
  | 'home'
  | 'gamepad'
  | 'film'
  | 'qrCode'
  | 'close'
  // Library redesign — knowledge-type icons and swipe actions, Feather-style.
  | 'utensils'
  | 'mapPin'
  | 'tag'
  | 'activity'
  | 'heart'
  | 'archive'
  | 'check'
  // Phase 3 knowledge types — recommendation_list, checklist, course, github_repo.
  | 'list'
  | 'checkSquare'
  | 'graduationCap'
  | 'code'
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

      // Member counts, People tab.
      case 'members':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
            <Circle cx="9" cy="7" r="4" />
            <Path d="M23 21v-2a4 4 0 0 0-3-3.87" />
            <Path d="M16 3.13a4 4 0 0 1 0 7.75" />
          </StrokeIcon>
        );

      // "Last activity" timestamps.
      case 'clock':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Circle cx="12" cy="12" r="10" />
            <Path d="M12 6v6l4 2" />
          </StrokeIcon>
        );

      // Travel space template.
      case 'compass':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Circle cx="12" cy="12" r="10" />
            <Path d="m16.24 7.76-2.12 6.36-6.36 2.12 2.12-6.36 6.36-2.12z" />
          </StrokeIcon>
        );

      // Study space template.
      case 'book':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
            <Path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
          </StrokeIcon>
        );

      // Work space template.
      case 'briefcase':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Rect x="2" y="7" width="20" height="14" rx="2" />
            <Path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16" />
          </StrokeIcon>
        );

      // Family space template.
      case 'home':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
            <Path d="M9 22V12h6v10" />
          </StrokeIcon>
        );

      // Gaming space template.
      case 'gamepad':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M17.32 5H6.68a4 4 0 0 0-3.978 3.59C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258A4 4 0 0 0 17.32 5Z" />
            <Line x1="6" y1="11" x2="10" y2="11" />
            <Line x1="8" y1="9" x2="8" y2="13" />
            <Line x1="15" y1="12" x2="15.01" y2="12" />
            <Line x1="18" y1="10" x2="18.01" y2="10" />
          </StrokeIcon>
        );

      // Movies space template.
      case 'film':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Rect x="2" y="2" width="20" height="20" rx="2.18" />
            <Line x1="7" y1="2" x2="7" y2="22" />
            <Line x1="17" y1="2" x2="17" y2="22" />
            <Line x1="2" y1="12" x2="22" y2="12" />
            <Line x1="2" y1="7" x2="7" y2="7" />
            <Line x1="2" y1="17" x2="7" y2="17" />
            <Line x1="17" y1="17" x2="22" y2="17" />
            <Line x1="17" y1="7" x2="22" y2="7" />
          </StrokeIcon>
        );

      // Join Space — Scan QR code (unimplemented, no camera capture surface exists yet).
      case 'qrCode':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Rect x="3" y="3" width="7" height="7" rx="1" />
            <Rect x="14" y="3" width="7" height="7" rx="1" />
            <Rect x="3" y="14" width="7" height="7" rx="1" />
            <Rect x="14" y="14" width="3" height="3" rx="0.5" />
            <Rect x="18" y="14" width="3" height="3" rx="0.5" />
            <Rect x="14" y="18" width="3" height="3" rx="0.5" />
            <Rect x="18" y="18" width="3" height="3" rx="0.5" />
          </StrokeIcon>
        );

      case 'close':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Line x1="18" y1="6" x2="6" y2="18" />
            <Line x1="6" y1="6" x2="18" y2="18" />
          </StrokeIcon>
        );

      // Recipe type tile / card.
      case 'utensils':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2" />
            <Path d="M7 2v20" />
            <Path d="M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7" />
          </StrokeIcon>
        );

      // Place type tile / card.
      case 'mapPin':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0Z" />
            <Circle cx="12" cy="10" r="3" />
          </StrokeIcon>
        );

      // Product type tile / card.
      case 'tag':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M20.59 13.41 13.42 20.58a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82Z" />
            <Line x1="7" y1="7" x2="7.01" y2="7" />
          </StrokeIcon>
        );

      // Workout type tile / card.
      case 'activity':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M22 12h-4l-3 9L9 3l-3 9H2" />
          </StrokeIcon>
        );

      // Favorite — Library swipe action and filter chip.
      case 'heart':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78Z" />
          </StrokeIcon>
        );

      // Archive — Library swipe action and filter chip.
      case 'archive':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Rect x="2" y="3" width="20" height="5" rx="1" />
            <Path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8" />
            <Line x1="10" y1="12" x2="14" y2="12" />
          </StrokeIcon>
        );

      // Multi-select checkbox tick.
      case 'check':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M20 6 9 17l-5-5" />
          </StrokeIcon>
        );

      // recommendation_list type tile / card.
      case 'list':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Line x1="8" y1="6" x2="21" y2="6" />
            <Line x1="8" y1="12" x2="21" y2="12" />
            <Line x1="8" y1="18" x2="21" y2="18" />
            <Line x1="3" y1="6" x2="3.01" y2="6" />
            <Line x1="3" y1="12" x2="3.01" y2="12" />
            <Line x1="3" y1="18" x2="3.01" y2="18" />
          </StrokeIcon>
        );

      // checklist type tile / card.
      case 'checkSquare':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M9 11l3 3L22 4" />
            <Path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </StrokeIcon>
        );

      // course type tile / card.
      case 'graduationCap':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M22 10 12 5 2 10l10 5 10-5Z" />
            <Path d="M6 12v5c0 1.5 2.5 3 6 3s6-1.5 6-3v-5" />
            <Path d="M22 10v6" />
          </StrokeIcon>
        );

      // github_repo type tile / card.
      case 'code':
        return (
          <StrokeIcon size={size} color={stroke} weight={weight} style={style}>
            <Path d="M16 18l6-6-6-6" />
            <Path d="M8 6l-6 6 6 6" />
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
