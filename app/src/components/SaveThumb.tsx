import React, { useState } from 'react';
import { Image, View } from 'react-native';

import { HatchThumb } from './HatchThumb';

interface SaveThumbProps {
  thumbnailUrl?: string | null;
  width?: number;
  height: number;
  radius?: number;
  /** Colour pip overlaid on the corner, keyed off knowledge type. */
  tint?: string;
}

/**
 * Shows a real thumbnail when available, falls back to the hatch placeholder.
 * The image loads in the background; on error it reverts to hatch silently.
 */
export function SaveThumb({ thumbnailUrl, width = 40, height, radius, tint }: SaveThumbProps) {
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
      ) : (
        <HatchThumb width={width} height={height} radius={radius} period={12} />
      )}
      {tint ? (
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
