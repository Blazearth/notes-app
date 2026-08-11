-- V20: Fix existing saves that were classified as "unusable" but incorrectly
-- marked status=failed. The pipeline ran successfully — it just determined
-- the content had nothing extractable (login walls, Shorts, empty transcripts).
-- status=ready is the correct state: the job finished, there is just no knowledge.
update saves
set status        = 'ready',
    error_message = null,
    error_code    = null
where knowledge_type = 'unusable'
  and status        = 'failed';
