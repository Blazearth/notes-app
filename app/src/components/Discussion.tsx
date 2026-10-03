import React, { useCallback, useEffect, useRef, useState } from 'react';
import { TextInput, View } from 'react-native';

import { repo } from '@/data';
import type { SaveComment } from '@/api/types';
import { getStore } from '@/local';
import type { OutboxPayloads } from '@/local/outbox';
import { writeComment, writeDeleteComment, writeVote } from '@/local/writes';
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
  /** Set by a tap, so a load landing after it cannot put back the old vote. */
  const votedHere = useRef(false);

  const load = useCallback(async () => {
    try {
      setComments(await repo.listComments(saveId));
    } catch {
      // A save whose comments will not load is still perfectly readable, so
      // this stays quiet rather than pushing an error over the content.
    }
    try {
      const server = await repo.getVote(saveId);
      // A vote still queued (made offline, or on a previous visit) is newer
      // than what the server holds: show it, and the score it will produce.
      const queued = (await getStore().readOutbox())
        .filter((entry) => entry.op === 'setVote' && entry.status === 'pending')
        .map((entry) => entry.payload as OutboxPayloads['setVote'])
        .filter((payload) => payload.saveId === saveId)
        .pop();
      if (votedHere.current) return;
      const mine = queued ? queued.value : server.myVote;
      setMyVote(mine);
      setScore(server.score - server.myVote + mine);
    } catch {
      // Same as comments: the save is readable without its score.
    }
  }, [saveId]);

  useEffect(() => {
    if (spaceId) void load();
  }, [spaceId, load]);

  const onVote = useCallback(
    (value: 1 | -1) => {
      // Tapping the vote you already hold clears it — the standard toggle, and
      // the server takes 0 for exactly this.
      const next: 1 | -1 | 0 = myVote === value ? 0 : value;
      votedHere.current = true;
      setMyVote(next);
      // The score is the sum of *everyone's* votes, so this can only guess at
      // the delta its own vote makes — which it can do exactly, because a vote
      // is a value the user sets rather than an increment (see `setVote`'s note
      // on why the server stores a row per voter and not a tally).
      setScore((current) => (current ?? 0) - myVote + next);
      writeVote(saveId, next);
    },
    [saveId, myVote],
  );

  const onSend = useCallback(() => {
    const body = draft.trim();
    if (!body) return;
    // Shown immediately and queued for delivery. The old version awaited the
    // POST and kept the draft on failure, which was the best it could do without
    // a queue — but "the request failed, here is your text back" still loses a
    // comment typed on a train. The `local:` id marks it as not-yet-confirmed;
    // the next successful load replaces it with the server's own copy.
    const localId = writeComment(saveId, body);
    setComments((current) => [
      ...current,
      {
        id: localId,
        userId: 'me',
        displayName: 'You',
        body,
        createdAt: new Date().toISOString(),
        mine: true,
      },
    ]);
    setDraft('');
  }, [saveId, draft]);

  const onDelete = useCallback(
    (commentId: string) => {
      setComments((current) => current.filter((c) => c.id !== commentId));
      // Queued even for a `local:` comment: its create is still on its way, and
      // dropping it here alone would let the create post it anyway. The queue
      // sends the delete after the create and resolves the real id.
      writeDeleteComment(saveId, commentId);
    },
    [saveId],
  );

  if (!spaceId) return null;

  const voteButton = (value: 1 | -1, label: string) => {
    const active = myVote === value;
    return (
      <Touchable
        accessibilityRole="button"
        accessibilityLabel={value === 1 ? 'Upvote' : 'Downvote'}
        accessibilityState={{ selected: active }}
        onPress={() => onVote(value)}
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
                onPress={() => onDelete(comment.id)}
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
