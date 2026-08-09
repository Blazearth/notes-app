package com.weavr.api.space;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.weavr.api.common.ForbiddenException;
import com.weavr.api.common.NotFoundException;
import com.weavr.api.profile.ProfileService;
import com.weavr.api.sync.TombstoneService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Spaces, membership, and the access-control boundary for both.
 *
 * <p>Spring connects to Postgres as a service role with {@code BYPASSRLS}, so
 * the policies in the migrations do not constrain anything here. <b>Every query
 * in this class must therefore establish membership itself</b> — which is why
 * {@link #requireRole} exists and why nothing takes a space id without passing
 * it through one of the two guards first.
 *
 * <h2>404 and 403 mean different things here</h2>
 * <p>A non-member gets {@link NotFoundException}: the existence of a Space they
 * were never invited to is not theirs to learn, and a 403 would confirm it.
 * A member without a sufficient role gets {@link ForbiddenException} — they
 * already know the Space exists, and hiding it would only confuse them.
 */
@Service
public class SpaceService {

    private static final Logger log = LoggerFactory.getLogger(SpaceService.class);

    /**
     * 16 bytes from a CSPRNG, base64url. An invite code is a bearer credential
     * — anyone holding it joins — so it is generated the way a session token
     * would be, not from a UUID or a timestamp.
     */
    private static final int INVITE_CODE_BYTES = 16;

    private final JdbcClient jdbc;
    private final ProfileService profiles;
    private final ObjectMapper objectMapper;
    private final TombstoneService tombstones;
    private final SecureRandom random = new SecureRandom();

    SpaceService(JdbcClient jdbc, ProfileService profiles, ObjectMapper objectMapper,
                 TombstoneService tombstones) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.objectMapper = objectMapper;
        this.tombstones = tombstones;
    }

    public record Space(UUID id, String name, String type, UUID ownerId, SpaceRole myRole,
                        int memberCount, int saveCount, Instant createdAt, Instant lastActivityAt) {
    }

    public record Member(UUID userId, String displayName, SpaceRole role, Instant joinedAt) {
    }

    public record Invite(UUID id, String code, SpaceRole role, Instant expiresAt,
                         Integer maxUses, int uses, boolean revoked, Instant createdAt) {
    }

    // ---------------------------------------------------------------- guards

    /**
     * @return the caller's role
     * @throws NotFoundException if they are not a member — deliberately not a
     *                           403, see the class javadoc
     */
    @Transactional(readOnly = true)
    public SpaceRole requireMember(UUID userId, UUID spaceId) {
        return roleOf(userId, spaceId)
                .orElseThrow(() -> new NotFoundException("Space not found"));
    }

    /** @throws ForbiddenException if they are a member but not senior enough */
    @Transactional(readOnly = true)
    public SpaceRole requireRole(UUID userId, UUID spaceId, SpaceRole required) {
        SpaceRole role = requireMember(userId, spaceId);
        if (!role.atLeast(required)) {
            throw new ForbiddenException(
                    "You need to be %s or above in this Space to do that.".formatted(required.db()));
        }
        return role;
    }

    @Transactional(readOnly = true)
    public Optional<SpaceRole> roleOf(UUID userId, UUID spaceId) {
        return jdbc.sql("select role from space_members where space_id = ? and user_id = ?")
                .param(spaceId)
                .param(userId)
                .query(String.class)
                .optional()
                .map(SpaceRole::fromDb);
    }

    // ----------------------------------------------------------------- CRUD

    @Transactional
    public Space create(UUID userId, String name, String type) {
        profiles.ensureExists(userId);
        UUID spaceId = jdbc.sql("""
                        insert into spaces (name, type, owner_id) values (?, ?, ?) returning id
                        """)
                .param(name.trim())
                .param(type == null || type.isBlank() ? "general" : type.trim())
                .param(userId)
                .query(UUID.class)
                .single();

        // The owner is a member row too, not an implicit special case. Every
        // membership query then has one shape, and `owner_id` is only about
        // who may delete the Space.
        addMember(spaceId, userId, SpaceRole.OWNER);
        recordActivity(spaceId, userId, null, "space_created", Map.of("name", name.trim()));

        log.info("Space {} created by {}", spaceId, userId);
        return get(userId, spaceId);
    }

    @Transactional(readOnly = true)
    public List<Space> listForUser(UUID userId) {
        return jdbc.sql("""
                        select s.id, s.name, s.type, s.owner_id, m.role, s.created_at,
                               (select count(*) from space_members mm where mm.space_id = s.id) as member_count,
                               (select count(*) from saves sv where sv.space_id = s.id) as save_count,
                               coalesce((select max(sa.created_at) from space_activity sa where sa.space_id = s.id),
                                        s.created_at) as last_activity_at
                        from spaces s
                        join space_members m on m.space_id = s.id
                        where m.user_id = ?
                        order by s.created_at desc
                        """)
                .param(userId)
                .query((rs, row) -> new Space(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getObject("owner_id", UUID.class),
                        SpaceRole.fromDb(rs.getString("role")),
                        rs.getInt("member_count"),
                        rs.getInt("save_count"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_activity_at").toInstant()))
                .list();
    }

    @Transactional(readOnly = true)
    public Space get(UUID userId, UUID spaceId) {
        SpaceRole role = requireMember(userId, spaceId);
        return jdbc.sql("""
                        select s.id, s.name, s.type, s.owner_id, s.created_at,
                               (select count(*) from space_members mm where mm.space_id = s.id) as member_count,
                               (select count(*) from saves sv where sv.space_id = s.id) as save_count,
                               coalesce((select max(sa.created_at) from space_activity sa where sa.space_id = s.id),
                                        s.created_at) as last_activity_at
                        from spaces s where s.id = ?
                        """)
                .param(spaceId)
                .query((rs, row) -> new Space(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getObject("owner_id", UUID.class),
                        role,
                        rs.getInt("member_count"),
                        rs.getInt("save_count"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_activity_at").toInstant()))
                .optional()
                .orElseThrow(() -> new NotFoundException("Space not found"));
    }

    @Transactional
    public Space rename(UUID userId, UUID spaceId, String name) {
        requireRole(userId, spaceId, SpaceRole.EDITOR);
        jdbc.sql("update spaces set name = ? where id = ?")
                .param(name.trim())
                .param(spaceId)
                .update();
        return get(userId, spaceId);
    }

    @Transactional
    public void delete(UUID userId, UUID spaceId) {
        requireRole(userId, spaceId, SpaceRole.OWNER);
        // Read the audience BEFORE the delete. `space_members` cascades away
        // with the Space, so a tombstone written afterwards would have nobody
        // to address and every other member would keep this Space in their
        // local cache forever.
        List<UUID> members = tombstones.membersOf(spaceId);
        // `saves.space_id` is ON DELETE SET NULL, so deleting a Space returns
        // its saves to their owners' private libraries rather than destroying
        // other people's content. Members and invites cascade.
        jdbc.sql("delete from spaces where id = ?").param(spaceId).update();
        tombstones.recordFor(members, TombstoneService.SPACE, spaceId.toString());
        log.info("Space {} deleted by {}", spaceId, userId);
    }

    // ------------------------------------------------------------- members

    @Transactional(readOnly = true)
    public List<Member> members(UUID userId, UUID spaceId) {
        requireMember(userId, spaceId);
        return jdbc.sql("""
                        select m.user_id, m.role, m.joined_at, p.display_name
                        from space_members m
                        join profiles p on p.id = m.user_id
                        where m.space_id = ?
                        order by m.joined_at
                        """)
                .param(spaceId)
                .query((rs, row) -> new Member(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        SpaceRole.fromDb(rs.getString("role")),
                        rs.getTimestamp("joined_at").toInstant()))
                .list();
    }

    @Transactional
    public void setRole(UUID actorId, UUID spaceId, UUID targetId, SpaceRole role) {
        requireRole(actorId, spaceId, SpaceRole.OWNER);
        if (role == SpaceRole.OWNER) {
            // Transferring ownership is a different operation with different
            // consequences (who can delete the Space), and quietly doing it
            // through a role edit is how a Space ends up with two owners and
            // nobody sure who is responsible for it.
            throw new ForbiddenException("Ownership can't be granted through a role change.");
        }
        if (targetId.equals(actorId)) {
            throw new ForbiddenException("You can't change your own role.");
        }
        int updated = jdbc.sql("update space_members set role = ? where space_id = ? and user_id = ?")
                .param(role.db())
                .param(spaceId)
                .param(targetId)
                .update();
        if (updated == 0) {
            throw new NotFoundException("That person isn't in this Space.");
        }
    }

    /**
     * Removing someone, or leaving yourself — the same row either way, so the
     * same method, with the authorisation differing by which it is.
     */
    @Transactional
    public void removeMember(UUID actorId, UUID spaceId, UUID targetId) {
        SpaceRole actorRole = requireMember(actorId, spaceId);
        boolean leaving = actorId.equals(targetId);
        if (!leaving && !actorRole.atLeast(SpaceRole.OWNER)) {
            throw new ForbiddenException("Only the owner can remove people from a Space.");
        }
        if (leaving && actorRole == SpaceRole.OWNER) {
            // Otherwise the Space has members and no one who can administer it.
            throw new ForbiddenException(
                    "The owner can't leave a Space. Delete it, or transfer it first.");
        }
        List<UUID> remaining = tombstones.membersOf(spaceId).stream()
                .filter(id -> !id.equals(targetId))
                .toList();

        jdbc.sql("delete from space_members where space_id = ? and user_id = ?")
                .param(spaceId)
                .param(targetId)
                .update();

        // Two different tombstones, because the same delete means two different
        // things. The people still in the Space lost a *member*; the person who
        // left lost the *Space* — their cached copy of it, its saves and its
        // member list all have to go, and a `space_member` tombstone would only
        // have taken their own row out of a list they can no longer see.
        tombstones.recordFor(remaining, TombstoneService.SPACE_MEMBER,
                spaceId + TombstoneService.KEY_SEPARATOR + targetId);
        tombstones.record(targetId, TombstoneService.SPACE, spaceId.toString());
    }

    @Transactional
    void addMember(UUID spaceId, UUID userId, SpaceRole role) {
        jdbc.sql("""
                        insert into space_members (space_id, user_id, role) values (?, ?, ?)
                        on conflict (space_id, user_id) do nothing
                        """)
                .param(spaceId)
                .param(userId)
                .param(role.db())
                .update();
    }

    // ------------------------------------------------------------- invites

    @Transactional
    public Invite createInvite(UUID userId, UUID spaceId, SpaceRole role,
                               Integer expiresInHours, Integer maxUses) {
        requireRole(userId, spaceId, SpaceRole.EDITOR);
        if (role == SpaceRole.OWNER) {
            throw new ForbiddenException("An invite can't grant ownership.");
        }

        byte[] bytes = new byte[INVITE_CODE_BYTES];
        random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant expiresAt = expiresInHours == null
                ? null : Instant.now().plusSeconds(expiresInHours * 3600L);

        UUID id = jdbc.sql("""
                        insert into space_invites (space_id, code, role, created_by, expires_at, max_uses)
                        values (?, ?, ?, ?, ?, ?)
                        returning id
                        """)
                .param(spaceId)
                .param(code)
                .param(role.db())
                .param(userId)
                .param(expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .param(maxUses)
                .query(UUID.class)
                .single();

        return new Invite(id, code, role, expiresAt, maxUses, 0, false, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<Invite> listInvites(UUID userId, UUID spaceId) {
        requireRole(userId, spaceId, SpaceRole.EDITOR);
        return jdbc.sql("""
                        select id, code, role, expires_at, max_uses, uses, revoked, created_at
                        from space_invites where space_id = ? order by created_at desc
                        """)
                .param(spaceId)
                .query((rs, row) -> new Invite(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        SpaceRole.fromDb(rs.getString("role")),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("max_uses") == null ? null : rs.getInt("max_uses"),
                        rs.getInt("uses"),
                        rs.getBoolean("revoked"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public void revokeInvite(UUID userId, UUID spaceId, UUID inviteId) {
        requireRole(userId, spaceId, SpaceRole.EDITOR);
        jdbc.sql("update space_invites set revoked = true where id = ? and space_id = ?")
                .param(inviteId)
                .param(spaceId)
                .update();
    }

    /** What the join screen shows before the user commits to joining. */
    public record InvitePreview(UUID spaceId, String spaceName, String invitedBy,
                                SpaceRole role, boolean alreadyMember) {
    }

    @Transactional(readOnly = true)
    public InvitePreview previewInvite(UUID userId, String code) {
        InviteRow invite = loadUsableInvite(code);
        return new InvitePreview(
                invite.spaceId(),
                invite.spaceName(),
                invite.createdByName(),
                invite.role(),
                roleOf(userId, invite.spaceId()).isPresent());
    }

    /**
     * Joins the Space the code belongs to.
     *
     * <p>The use counter is incremented with the ceiling in the {@code where}
     * clause rather than read-then-written, so a link posted in a group chat and
     * tapped by five people at once cannot let all five past a
     * {@code max_uses = 1}.
     */
    @Transactional
    public Space acceptInvite(UUID userId, String code) {
        profiles.ensureExists(userId);
        InviteRow invite = loadUsableInvite(code);

        if (roleOf(userId, invite.spaceId()).isPresent()) {
            // Already in. Re-tapping your own invite link is not an error, and
            // it must not spend a use.
            return get(userId, invite.spaceId());
        }

        int claimed = jdbc.sql("""
                        update space_invites
                        set uses = uses + 1
                        where id = ?
                          and revoked = false
                          and (expires_at is null or expires_at > now())
                          and (max_uses is null or uses < max_uses)
                        """)
                .param(invite.id())
                .update();
        if (claimed == 0) {
            throw new NotFoundException("That invite link is no longer valid.");
        }

        addMember(invite.spaceId(), userId, invite.role());
        recordActivity(invite.spaceId(), userId, null, "member_joined", Map.of());
        log.info("User {} joined space {} via invite {}", userId, invite.spaceId(), invite.id());
        return get(userId, invite.spaceId());
    }

    private record InviteRow(UUID id, UUID spaceId, String spaceName, String createdByName,
                             SpaceRole role) {
    }

    /**
     * Expired, revoked and exhausted invites are all reported as "no longer
     * valid" rather than distinguished. The distinction is of no use to the
     * person holding the link and of some use to someone guessing codes.
     */
    private InviteRow loadUsableInvite(String code) {
        return jdbc.sql("""
                        select i.id, i.space_id, i.role, s.name as space_name,
                               coalesce(p.display_name, 'A Weavr user') as created_by_name
                        from space_invites i
                        join spaces s on s.id = i.space_id
                        join profiles p on p.id = i.created_by
                        where i.code = ?
                          and i.revoked = false
                          and (i.expires_at is null or i.expires_at > now())
                          and (i.max_uses is null or i.uses < i.max_uses)
                        """)
                .param(code)
                .query((rs, row) -> new InviteRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("space_id", UUID.class),
                        rs.getString("space_name"),
                        rs.getString("created_by_name"),
                        SpaceRole.fromDb(rs.getString("role"))))
                .optional()
                .orElseThrow(() -> new NotFoundException("That invite link is no longer valid."));
    }

    // ------------------------------------------------------------ activity

    /**
     * Meaningful events only — joined, saved, completed, commented, voted.
     * Never "viewed" or any per-scroll signal: a feed that logs everything is
     * noise nobody reads, and it is also the fastest-growing table on a 500 MB
     * free tier.
     *
     * <p>Failures are swallowed. The activity feed is a record of things that
     * already happened; failing the thing itself because its record could not
     * be written would be exactly backwards.
     *
     * <p><b>{@code REQUIRES_NEW}, and that is what makes the swallow real.</b>
     * Postgres aborts an entire transaction on any failed statement — every
     * subsequent command returns "current transaction is aborted" — so a
     * {@code catch} around a failing insert contains nothing at all when the
     * insert ran inside the caller's transaction. Recording an activity row in
     * its own transaction is the only way "this must never break the thing it
     * describes" can be true. Learned by watching a foreign-key violation here
     * take down every save into a Space.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordActivity(UUID spaceId, UUID userId, UUID saveId, String type,
                               Map<String, Object> payload) {
        if (spaceId == null) {
            return;
        }
        try {
            jdbc.sql("""
                            insert into space_activity (space_id, user_id, save_id, type, payload)
                            values (?, ?, ?, ?, ?::jsonb)
                            """)
                    .param(spaceId)
                    .param(userId)
                    .param(saveId)
                    .param(type)
                    .param(payload == null || payload.isEmpty()
                            ? "{}" : objectMapper.writeValueAsString(payload))
                    .update();
        } catch (RuntimeException e) {
            log.warn("Could not record {} activity in space {}: {}", type, spaceId, e.toString());
        }
    }

    public record ActivityEntry(UUID id, UUID userId, String displayName, UUID saveId,
                                String saveTitle, String type, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<ActivityEntry> activity(UUID userId, UUID spaceId, int limit) {
        requireMember(userId, spaceId);
        return jdbc.sql("""
                        select a.id, a.user_id, a.save_id, a.type, a.created_at,
                               coalesce(p.display_name, 'A Weavr user') as display_name,
                               coalesce(s.structured_data ->> 'title',
                                        s.structured_data ->> 'name') as save_title
                        from space_activity a
                        join profiles p on p.id = a.user_id
                        left join saves s on s.id = a.save_id
                        where a.space_id = ?
                        order by a.created_at desc
                        limit ?
                        """)
                .param(spaceId)
                .param(Math.clamp(limit, 1, 100))
                .query((rs, row) -> new ActivityEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getObject("save_id", UUID.class),
                        rs.getString("save_title"),
                        rs.getString("type"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
