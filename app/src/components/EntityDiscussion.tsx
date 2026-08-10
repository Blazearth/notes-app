import React, { useCallback, useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';

import { repo } from '@/data';
import type { EntityComment } from '@/api/types';
import { writeDeleteEntityComment, writeEntityComment } from '@/local/writes';
import { AppText } from '@/components/AppText';
import { Avatar } from '@/components/Avatar';
import { Touchable } from '@/components/Touchable';
import { relativeTime } from '@/saves/format';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * S3 — the thread on one merged entity ({@code docs/knowledge-spaces.md}).
 *
 * **Why this is not `Discussion`.** That one hangs off a save, which was the
 * right home when a Space was a folder of saves. "Blue Box starts slow but the
 * second half is worth it" is a remark about Blue Box — and Blue Box is
 * assembled from several members' saves, so filing it under one of them buries
 * it where the next reader has no reason to look, and loses it entirely if that
 * save later leaves the Space.
 *
 * Three deliberate differences from the save thread:
 *
 * - **No vote.** `save_votes` is per save; an entity has no equivalent table and
 *   inventing one to keep the two components symmetrical would be a schema
 *   decision made for a layout reason.
 * - **Space-scoped, where entity *state* is global.** A status is a fact about
 *   the person and shows in every Space containing that entity (which the
 *   People tab says out loud); a remark was said in a room and stays in it.
 * - **Not cached.** `entity_comments` is outside the delta, like save comments,
 *   so this holds its own copy and the queue owns delivery — a remark typed on
 *   a train arrives when there is a network rather than being lost with the
 *   draft.
 */
export function EntityDiscussion({
  spaceId,
  entityKey,
  entityName,
  onCountChange,
}: {
  spaceId: string;
  entityKey: string;
  entityName: string;
  /** So the collapsed row's "2 comments" follows an optimistic send without a refetch. */
  onCountChange?: (delta: number) => void;
}) {
  const { palette, radius, spacing } = useTheme();

  const [comments, setComments] = useState<EntityComment[] | null>(null);
  const [draft, setDraft] = useState('');

  const load = useCallback(async () => {
    try {
      setComments(await repo.listEntityComments(spaceId, entityKey));
    } catch {
      // The entity itself is perfectly readable without its thread, and the
      // rest of this screen is derived locally. An error card here would take
      // away everything to report what one section could not load.
      setComments((current) => current ?? []);
    }
  }, [spaceId, entityKey]);

  useEffect(() => {
    void load();
  }, [load]);

  const onSend = useCallback(() => {
    const body = draft.trim();
    if (!body) return;
    // Shown immediately, queued for delivery. `pending:` marks it as
    // not-yet-confirmed; the next successful load replaces it with the server's
    // own copy, which is also where the real id comes from.
    setComments((current) => [
      ...(current ?? []),
      {
        id: `pending:${Date.now()}`,
        entityKey,
        userId: 'me',
        displayName: 'You',
        body,
        createdAt: new Date().toISOString(),
        mine: true,
      },
    ]);
    setDraft('');
    onCountChange?.(1);
    writeEntityComment(spaceId, entityKey, body);
  }, [spaceId, entityKey, draft, onCountChange]);

  const onDelete = useCallback(
    (commentId: string) => {
      setComments((current) => (current ?? []).filter((c) => c.id !== commentId));
      onCountChange?.(-1);
      // A comment that never reached the server has nothing to delete there.
      if (commentId.startsWith('pending:')) return;
      writeDeleteEntityComment(spaceId, commentId);
    },
    [spaceId, onCountChange],
  );

  return (
    <View style={{ gap: spacing.sm, marginTop: spacing.xs }}>
      {(comments ?? []).map((comment) => (
        <View key={comment.id} style={{ flexDirection: 'row', gap: spacing.smd }}>
          <Avatar id={comment.userId} name={comment.displayName} size={22} />
          <View style={{ flex: 1 }}>
            <AppText variant="bodySmall">{comment.body}</AppText>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: 1 }}>
              <AppText variant="caption" tone="muted" style={{ fontSize: 11 }}>
                {comment.displayName} · {relativeTime(comment.createdAt)}
              </AppText>
              {comment.mine ? (
                <Touchable
                  accessibilityRole="button"
                  accessibilityLabel={`Delete your comment on ${entityName}`}
                  onPress={() => onDelete(comment.id)}
                  haptic="medium"
                >
                  <AppText variant="caption" style={{ color: palette.danger, fontSize: 11 }}>
                    Delete
                  </AppText>
                </Touchable>
              ) : null}
            </View>
          </View>
        </View>
      ))}

      {comments !== null && comments.length === 0 ? (
        <AppText variant="caption" tone="muted" style={{ fontSize: 11.5 }}>
          Nothing said about {entityName} yet.
        </AppText>
      ) : null}

      <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'flex-end' }}>
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder={`Say something about ${entityName}`}
          placeholderTextColor={palette.textFaint}
          accessibilityLabel={`Comment on ${entityName}`}
          multiline
          maxLength={2000}
          style={{
            flex: 1,
            color: palette.text,
            backgroundColor: palette.surface,
            borderWidth: 1,
            borderColor: palette.border,
            borderRadius: radius.sm,
            paddingHorizontal: spacing.smd,
            paddingVertical: spacing.sm,
            fontSize: 13.5,
            maxHeight: 110,
          }}
        />
        <Touchable
          accessibilityRole="button"
          accessibilityLabel={`Send comment on ${entityName}`}
          onPress={onSend}
          haptic="selection"
          style={{
            paddingVertical: spacing.sm,
            paddingHorizontal: spacing.md,
            borderRadius: radius.sm,
            backgroundColor: draft.trim() ? palette.accent : palette.surfaceVariant,
          }}
        >
          <AppText
            variant="caption"
            style={{ color: draft.trim() ? palette.onAccent : palette.textFaint }}
          >
            Send
          </AppText>
        </Touchable>
      </View>
    </View>
  );
}
