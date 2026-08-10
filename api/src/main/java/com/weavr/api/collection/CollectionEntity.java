package com.weavr.api.collection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One merged entity — a thing the user is collecting, appearing in one or more
 * sources. See {@code docs/knowledge-collections.md} ("What merging produces
 * per entity").
 *
 * @param entityKey    {@link Entities#key}'s output — stable, and how the
 *                     entity endpoint is filtered by facet
 * @param name         the most common surface form across sources (ties:
 *                     earliest save)
 * @param kind         first non-{@code [unclear]} value across sources, or the
 *                     type's fixed kind for a shape with no per-item kind
 *                     field (checklist's {@code "task"})
 * @param fields       every other field the item carried, rolled up: a list
 *                     field is the union across sources, a scalar field is the
 *                     first non-{@code [unclear]} value. Generic over whatever
 *                     the registry's item schema carries — {@code genre} for a
 *                     recommendation, {@code cost}/{@code area} for a place —
 *                     so a new item field needs no change here.
 * @param sources      every source's own, un-merged copy of the item —
 *                     {@code reason} attributed to Reel A is never blended
 *                     with {@code reason} from Reel B. In a Space-scoped merge
 *                     each source also carries {@code addedBy} (S1) — "saved by
 *                     Maya" — which the personal endpoints omit, because there
 *                     it would name the caller on every row.
 * @param sourceCount  distinct saves this entity appears in — "recommended in
 *                     3 saves", literally, not a synthetic confidence score
 * @param state        the caller's own K2 entity state ({@code done},
 *                     {@code rating}, …), joined in at read time by
 *                     {@code CollectionService.entities} — {@code null} (and
 *                     so absent on the wire) when the caller has never
 *                     touched this entity. Never produced by the pure merge
 *                     functions themselves, which have no database.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionEntity(
        String entityKey,
        String name,
        String kind,
        Map<String, Object> fields,
        List<Source> sources,
        int sourceCount,
        Map<String, Object> state) {

    /**
     * One save's own copy of the item.
     *
     * @param addedBy who saved it — {@code null}, and so absent on the wire, on
     *                every personal read. It is only ever non-null for a
     *                Space-scoped merge ({@code docs/knowledge-spaces.md}, S1),
     *                where "who put this here" is the whole point and comes
     *                free from the saves already carrying {@code user_id}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(UUID saveId, Instant savedAt, Map<String, Object> item, UUID addedBy) {

        /** Personal reads: attribution would name the caller on every row. */
        public Source(UUID saveId, Instant savedAt, Map<String, Object> item) {
            this(saveId, savedAt, item, null);
        }
    }
}
