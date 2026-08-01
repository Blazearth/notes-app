import React, { useCallback, useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';

import { repo } from '@/data';
import type { SaveComment } from '@/api/types';
import { AppText } from '@/components/AppText';
import { Card } from '@/components/Card';
import { SectionLabel } from '@/components/SectionLabel';
import { Touchable } from '@/components/Touchable';
import { relativeTime } from '@/saves/format';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Comments and a vote on one save.
 *
 * <p>Shown only for a save in a Space: a comment thread on something nobody
 * else can see is a note to self, which the server allows but which does not
 * earn a section of its own on the screen.
 */
export function Discussion({ saveId, spaceId }: { saveId: string; spaceId?: string }) {
  const { palette, radius, spacing } = useTheme();

  const [comments, setComments] = useState<SaveComment[]>([]);
  const [draft, setDraft] = useState('');
  const [score, setScore] = useState<number | null>(null);
  const [myVote, setMyVote] = useState<1 | -1 | 0>(0);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setComments(await repo.listComments(saveId));
    } catch {
      // A save whose comments will not load is still perfectly readable, so
      // this stays quiet rather than pushing an error over the content.
    }
  }, [saveId]);

  useEffect(() => {
    if (spaceId) void load();
  }, [spaceId, load]);

  const onVote = useCallback(
    async (value: 1 | -1) => {
      // Tapping the vote you already hold clears it — the standard toggle, and
      // the server takes 0 for exactly this.
      const next: 1 | -1 | 0 = myVote === value ? 0 : value;
      const previous = myVote;
      setMyVote(next);
      try {
        const result = await repo.setVote(saveId, next);
        setScore(result.score);
      } catch {
        setMyVote(previous);
      }
    },
    [saveId, myVote],
  );

  const onSend = useCallback(async () => {
    const body = draft.trim();
    if (!body || busy) return;
    setBusy(true);
    try {
      const created = await repo.addComment(saveId, body);
      setComments((current) => [...current, created]);
      setDraft('');
    } catch {
      // Keep the draft. Losing what someone typed because a request failed is
      // the one outcome worth going out of the way to prevent.
    } finally {
      setBusy(false);
    }
  }, [saveId, draft, busy]);

  const onDelete = useCallback(
    async (commentId: string) => {
      setComments((current) => current.filter((c) => c.id !== commentId));
      try {
        await repo.deleteComment(saveId, commentId);
      } catch {
        await load();
      }
    },
    [saveId, load],
  );

  if (!spaceId) return null;

  const voteButton = (value: 1 | -1, label: string) => {
    const active = myVote === value;
    return (
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={value === 1 ? 'Upvote' : 'Downvote'}
        accessibilityState={{ selected: active }}
        onPress={() => void onVote(value)}
        haptic="selection"
        style={{
          paddingVertical: spacing.xs + 2,
          paddingHorizontal: spacing.md,
          borderRadius: radius.sm,
          borderWidth: 1,
          borderColor: active ? palette.accent : palette.border,
          backgroundColor: active ? palette.accent : palette.surface,
        }}
      >
        <AppText variant="caption" style={{ color: active ? palette.onAccent : palette.textMuted }}>
          {label}
        </AppText>
      </Touchable>
    );
  };

  return (
    <View style={{ marginBottom: spacing.xl }}>
      <SectionLabel>Discussion</SectionLabel>

      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: spacing.sm,
          marginBottom: spacing.md,
        }}
      >
        {voteButton(1, 'Worth it')}
        {voteButton(-1, 'Skip it')}
        {score !== null ? (
          <AppText variant="caption" tone="muted">
            {score > 0 ? `+${score}` : score}
          </AppText>
        ) : null}
      </View>

      <View style={{ gap: spacing.sm, marginBottom: spacing.md }}>
        {comments.map((comment) => (
          <Card key={comment.id} radius={radius.md}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
                marginBottom: 2,
              }}
            >
              <AppText variant="label" tone="accent" style={{ fontSize: 11 }}>
                {comment.displayName}
              </AppText>
              <AppText variant="caption" tone="muted" style={{ fontSize: 10.5 }}>
                {relativeTime(comment.createdAt)}
              </AppText>
            </View>
            <AppText variant="bodySmall">{comment.body}</AppText>
            {comment.mine ? (
              <Touchable
                accessibilityRole="button"
                accessibilityLabel="Delete comment"
                onPress={() => void onDelete(comment.id)}
                haptic="medium"
                style={{ alignSelf: 'flex-start', marginTop: spacing.xs }}
              >
                <AppText variant="caption" style={{ color: palette.danger, fontSize: 11 }}>
                  Delete
                </AppText>
              </Touchable>
            ) : null}
          </Card>
        ))}
      </View>

      <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'flex-end' }}>
        <TextInput
          value={draft}
          onChangeText={setDraft}
          placeholder="Add a comment"
          placeholderTextColor={palette.textFaint}
          multiline
          maxLength={2000}
          style={{
            flex: 1,
            color: palette.text,
            backgroundColor: palette.surface,
            borderWidth: 1,
            borderColor: palette.border,
            borderRadius: radius.sm,
            paddingHorizontal: spacing.md,
            paddingVertical: spacing.smd,
            fontSize: 14,
            maxHeight: 120,
          }}
        />
        <Touchable
          accessibilityRole="button"
          accessibilityLabel="Send comment"
          onPress={onSend}
          haptic="selection"
          style={{
            paddingVertical: spacing.smd,
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
