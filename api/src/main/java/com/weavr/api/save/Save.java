package com.weavr.api.save;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A single saved item.
 *
 * <p>Two columns that exist in {@code V1__init.sql} are deliberately <em>not</em>
 * mapped here:
 * <ul>
 *   <li>{@code embedding vector(1536)} — pgvector has no native Hibernate type.
 *       It is read and written through {@code JdbcTemplate}/{@code JdbcClient}
 *       in the search and embedding code, which also owns the similarity
 *       queries. Mapping it here would buy nothing and break bootstrap.</li>
 *   <li>{@code search_tsv} — a generated stored column. The database owns it.</li>
 * </ul>
 */
@Entity
@Table(name = "saves")
public class Save {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "space_id")
    private UUID spaceId;

    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_url")
    private String sourceUrl;

    /** Whatever text arrived with the share: caption, typed note, on-device OCR. */
    @Column(name = "raw_caption")
    private String rawCaption;

    @Column(name = "media_storage_path")
    private String mediaStoragePath;

    /**
     * Client-supplied {@code Idempotency-Key}, so a retried {@code POST
     * /v1/saves} — the share extension's background URLSession retries by
     * design — lands on this row instead of creating a duplicate.
     */
    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "status", nullable = false)
    private SaveStatus status = SaveStatus.PROCESSING;

    /** Keyed against a registry in code, so a new type is data, not a migration. */
    @Column(name = "knowledge_type")
    private String knowledgeType;

    @Column(name = "confidence")
    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_data", nullable = false)
    private Map<String, Object> structuredData = new HashMap<>();

    @Column(name = "lifecycle_status", nullable = false)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.SAVED;

    /** Which model served this save, so escalation rate is measured, not guessed. */
    @Column(name = "model_used")
    private String modelUsed;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "favorite", nullable = false)
    private boolean favorite = false;

    @Column(name = "archived", nullable = false)
    private boolean archived = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Save() {
        // for JPA
    }

    private Save(UUID userId, SourceType sourceType) {
        this.userId = userId;
        this.sourceType = sourceType;
    }

    /**
     * Creates an accepted-but-unprocessed save. The pipeline fills in
     * everything below {@code status}.
     */
    public static Save accepted(UUID userId, SourceType sourceType, String sourceUrl,
                                String rawCaption, UUID spaceId, String idempotencyKey) {
        Save save = new Save(userId, sourceType);
        save.sourceUrl = sourceUrl;
        save.rawCaption = rawCaption;
        save.spaceId = spaceId;
        save.idempotencyKey = idempotencyKey;
        save.status = SaveStatus.PROCESSING;
        return save;
    }

    /**
     * Creates a save for an uploaded image (screenshot share).
     *
     * <p><strong>The id is deliberately left unset, and the image must already
     * be uploaded before this is called.</strong> Both halves of that are
     * load-bearing, and each one broke the endpoint on its own:
     * <ul>
     *   <li>This entity has no {@code @Version} field, so Spring Data's
     *       {@code isNew()} is exactly {@code id == null}. Pre-assigning an id
     *       here — tempting, since the storage path used to be derived from it
     *       — routes {@code save()} through {@code merge()} instead of
     *       {@code persist()}, and merging a detached entity whose row does not
     *       exist throws {@code StaleObjectStateException}. That 500s every
     *       upload, not just concurrent ones.</li>
     *   <li>Persisting first and filling the URL in afterwards does not work
     *       either: {@code persist()} snapshots the field state at persist
     *       time, so the INSERT carries a null {@code source_url} and trips the
     *       {@code saves_has_content} check constraint before the follow-up
     *       UPDATE can run.</li>
     * </ul>
     * So the object key is its own random UUID rather than the save id, and the
     * row is inserted once, fully formed.
     *
     * @param publicUrl the Supabase Storage public URL — stored as
     *                  {@code source_url}, {@code thumbnail_url} and
     *                  {@code media_storage_path} so the card renders the real
     *                  screenshot immediately and the pipeline can re-download
     *                  the bytes for the vision call.
     */
    public static Save acceptedImage(UUID userId, String publicUrl, UUID spaceId) {
        Save save = new Save(userId, SourceType.IMAGE);
        save.sourceUrl = publicUrl;
        save.thumbnailUrl = publicUrl;
        save.mediaStoragePath = publicUrl;
        save.spaceId = spaceId;
        save.status = SaveStatus.PROCESSING;
        return save;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getRawCaption() {
        return rawCaption;
    }

    public String getMediaStoragePath() {
        return mediaStoragePath;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public SaveStatus getStatus() {
        return status;
    }

    public void setStatus(SaveStatus status) {
        this.status = status;
    }

    public String getKnowledgeType() {
        return knowledgeType;
    }

    public Double getConfidence() {
        return confidence;
    }

    public Map<String, Object> getStructuredData() {
        return structuredData;
    }

    /** Stamps {@code updatedAt} for the same reason {@link #setLifecycleStatus} does. */
    public void setStructuredData(Map<String, Object> structuredData) {
        this.structuredData = structuredData;
        this.updatedAt = Instant.now();
    }

    /** Stamps {@code updatedAt} for the same reason {@link #setLifecycleStatus} does. */
    public void setRawCaption(String rawCaption) {
        this.rawCaption = rawCaption;
        this.updatedAt = Instant.now();
    }

    /** Records where the original media file is stored (e.g. Supabase Storage path). */
    public void setMediaStoragePath(String mediaStoragePath) {
        this.mediaStoragePath = mediaStoragePath;
        this.updatedAt = Instant.now();
    }

    public LifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    /**
     * Also stamps {@code updatedAt}, because the "Continue" rail orders on it
     * and because the response returned to the caller would otherwise carry the
     * stale value — the database trigger fixes the row, not the object already
     * on its way back.
     */
    public void setLifecycleStatus(LifecycleStatus lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
        this.updatedAt = Instant.now();
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public void setSpaceId(UUID spaceId) {
        this.spaceId = spaceId;
    }

    public boolean isFavorite() {
        return favorite;
    }

    /** Stamps {@code updatedAt} for the same reason {@link #setLifecycleStatus} does. */
    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
