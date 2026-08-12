-- ============================================================
-- media_fingerprint: validate the payload of the seeking video algorithm
-- FFMPEG_LANCZOS_PHASH_256_FRAMES_V2 stores exactly what FRAMES_V1 stored - one
-- row per sampled frame, a 32-byte 256-bit pHash in hash_bytes and a 1024-byte
-- 32x32 luminance sample in sample_bytes - and differs only in how the frame was
-- reached: an input seek per position instead of decoding the file sequentially
-- and selecting. That difference is invisible to this table and is exactly why
-- the identifier changed: seeking reproduced the sequential frame 265 times out
-- of 267 measured, and twice it did not, so the two are separate families.
--
-- Without this the new rows would pass the CHECK untested - an algorithm the
-- constraint does not name satisfies every branch of it - and the guarantee that
-- a stored fingerprint is physically well formed would quietly stop applying to
-- the only algorithm still producing rows.
--
-- Purely additive, as V3 was when the video algorithm arrived: no row is read,
-- rewritten or deleted, and the V1 rows keep being valid V1 rows. They are left
-- exactly as they are - the backlog already treats a file without a V2 row as
-- pending, so the videos fingerprinted under V1 come back on their own, and
-- until they do their old rows stay queryable as the historical family they are.
-- ============================================================
ALTER TABLE media_fingerprint DROP CONSTRAINT ck_media_fingerprint_phash_payload;

ALTER TABLE media_fingerprint ADD CONSTRAINT ck_media_fingerprint_phash_payload
    CHECK (
        (algorithm <> 'FFMPEG_LANCZOS_PHASH_256_V1'
            OR (hash_bytes IS NOT NULL AND sample_bytes IS NOT NULL
                AND octet_length(hash_bytes) = 32 AND octet_length(sample_bytes) = 1024))
        AND
        (algorithm <> 'FFMPEG_LANCZOS_PHASH_256_FRAMES_V1'
            OR (hash_bytes IS NOT NULL AND sample_bytes IS NOT NULL
                AND octet_length(hash_bytes) = 32 AND octet_length(sample_bytes) = 1024))
        AND
        (algorithm <> 'FFMPEG_LANCZOS_PHASH_256_FRAMES_V2'
            OR (hash_bytes IS NOT NULL AND sample_bytes IS NOT NULL
                AND octet_length(hash_bytes) = 32 AND octet_length(sample_bytes) = 1024))
    );