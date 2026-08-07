import React from 'react';
import { View } from 'react-native';

import type { CollectionNodeResponse } from '@/api/types';
import { collectionTypeMeta } from '@/collections/collectionMeta';
import { saveTypeMeta } from '@/saves/saveTypeMeta';
import { useTheme } from '@/theme/ThemeProvider';
import { AppText } from './AppText';
import { Card } from './Card';
import { Glyph } from './Glyph';
import { Touchable } from './Touchable';

/**
 * The Library's top-level tile for an entity-bearing type — "Recommendations
 * — 5 titles · 1 watched · 2 sources" instead of a row per save.
 *
 * `node` is a top-level `CollectionNodeResponse` (`node.id === type`, per
 * `CollectionController`'s scheme), so its icon and colour reuse
 * `saveTypeMeta` rather than inventing a second palette for the same
 * `knowledgeType`.
 */
export function CollectionCard({
  node,
  type,
  onPress,
}: {
  node: CollectionNodeResponse;
  type: string;
  onPress: () => void;
}) {
  const { palette, radius, spacing, icon } = useTheme();
  const typeMeta = saveTypeMeta(type);
  const collMeta = collectionTypeMeta(type);

  const parts = [`${node.entityCount} ${collMeta.entityNoun(node.entityCount)}`];
  if (node.doneCount > 0) parts.push(`${node.doneCount} ${collMeta.doneNoun}`);
  parts.push(`${node.sourceCount} ${node.sourceCount === 1 ? 'source' : 'sources'}`);

  return (
    <Card padding={0} radius={radius.md}>
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={`${node.name}, ${parts.join(', ')}`}
        onPress={onPress}
        haptic="selection"
        style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.smd, padding: spacing.md }}
      >
        <View
          style={{
            width: 40,
            height: 40,
            borderRadius: radius.sm,
            backgroundColor: `${typeMeta.color}26`,
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <Glyph name={typeMeta.glyph} size={18} weight={2} color={typeMeta.color} />
        </View>
        <View style={{ flex: 1 }}>
          <AppText variant="cardTitle" numberOfLines={1}>
            {node.name}
          </AppText>
          <AppText variant="caption" tone="muted" numberOfLines={1} style={{ marginTop: 2 }}>
            {parts.join(' · ')}
          </AppText>
        </View>
        <View style={{ transform: [{ scaleX: -1 }] }}>
          <Glyph name="chevron" size={icon.sm} color={palette.textFaint} />
        </View>
      </Touchable>
    </Card>
  );
}
