-- ============================================================
-- media_fingerprint: accept the video frame algorithm's payload
-- The V1 payload CHECK only knew the photo algorithm
-- (FFMPEG_LANCZOS_PHASH_256_V1). The video algorithm
-- (FFMPEG_LANCZOS_PHASH_256_FRAMES_V1) stores one row per sampled frame with the
-- same physical payload as a photo frame - a 32-byte 256-bit pHash in hash_bytes
-- and a 1024-byte 32x32 luminance sample in sample_bytes - it just has several
-- rows per file (sample_index / position_ms). Extend the CHECK so both
-- algorithms are validated; every other algorithm stays unconstrained here, as
-- before. Rows of a new algorithm are never compared to these, so this is
-- purely additive.
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
    );