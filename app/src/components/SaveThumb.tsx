import React, { useState } from 'react';
import { Image, View } from 'react-native';

import type { GlyphName } from './Glyph';
import { Glyph } from './Glyph';
import { HatchThumb } from './HatchThumb';

interface SaveThumbProps {
  thumbnailUrl?: string | null;
  width?: number;
  height: number;
  radius?: number;
  /** Colour pip overlaid on the corner, keyed off knowledge type. */
  tint?: string;
  /**
   * A colored file-type icon to show in place of the hatch placeholder, per
   * `saveTypeMeta` — e.g. a book glyph for a `book` save with no real
   * thumbnail. Omit to fall back to the plain hatch stripes (used where the
   * caller has no knowledge type to key off, e.g. a still-`processing` save).
   */
  glyph?: GlyphName;
}

/**
 * Shows a real thumbnail when available. Falling back: a tinted tile with the
 * knowledge type's icon when `glyph` is given (a real preview beats a
 * placeholder, but a labelled placeholder beats an anonymous one), otherwise
 * the hatch stripes. The image loads in the background; on error it reverts
 * to the fallback silently.
 */
export function SaveThumb({ thumbnailUrl, width = 40, height, radius, tint, glyph }: SaveThumbProps) {
  const [failed, setFailed] = useState(false);

  const showImage = thumbnailUrl && !failed;

  return (
    <View style={{ width, height, position: 'relative' }}>
      {showImage ? (
        <Image
          source={{ uri: thumbnailUrl }}
          style={{ width, height, borderRadius: radius ?? 6 }}
          resizeMode="cover"
          onError={() => setFailed(true)}
        />
      ) : glyph && tint ? (
        <View
          style={{
            width,
            height,
            borderRadius: radius ?? 6,
            backgroundColor: `${tint}26`,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name={glyph} size={Math.round(width * 0.45)} weight={2} color={tint} />
        </View>
      ) : (
        <HatchThumb width={width} height={height} radius={radius} period={12} />
      )}
      {tint && (showImage || !glyph) ? (
        <View
          style={{
            position: 'absolute',
            right: -2,
            bottom: -2,
            width: 10,
            height: 10,
            borderRadius: 5,
            backgroundColor: tint,
          }}
        />
      ) : null}
    </View>
  );
}
