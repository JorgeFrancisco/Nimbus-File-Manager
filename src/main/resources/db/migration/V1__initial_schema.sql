-- ============================================================
-- catalog_file
-- ============================================================
CREATE TABLE catalog_file (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    public_id UUID NOT NULL,
    file_key VARCHAR(500) NOT NULL UNIQUE,
    file_name VARCHAR(500) NOT NULL,
    extension VARCHAR(50) NOT NULL,
    size_bytes BIGINT NOT NULL,

    sha256 VARCHAR(64),
    md5 VARCHAR(32),
    mime_type VARCHAR(100),

    created_at TIMESTAMP,
    modified_at TIMESTAMP NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    file_type VARCHAR(30) NOT NULL,

    lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',

    last_analysis TIMESTAMP,
    analysis_version VARCHAR(50),

    CONSTRAINT uk_catalog_file_public_id UNIQUE (public_id),

    CONSTRAINT ck_catalog_file_lifecycle_status
        CHECK (lifecycle_status IN ('ACTIVE', 'MISSING', 'DELETED'))
);

CREATE INDEX ix_catalog_file_type ON catalog_file(file_type);
CREATE INDEX ix_catalog_file_modified_at ON catalog_file(modified_at);
CREATE INDEX ix_catalog_file_sha256 ON catalog_file(sha256);
CREATE INDEX ix_catalog_file_md5 ON catalog_file(md5);
CREATE INDEX ix_catalog_file_mime_type ON catalog_file(mime_type);
CREATE INDEX ix_catalog_file_lifecycle_sha256_size ON catalog_file(lifecycle_status, sha256, size_bytes);
CREATE INDEX ix_catalog_file_lifecycle_id ON catalog_file(lifecycle_status, id);
CREATE INDEX ix_catalog_file_lower_extension ON catalog_file(lower(extension));

-- ============================================================
-- catalog_file_location
-- ============================================================
CREATE TABLE catalog_file_location (
    catalog_file_id BIGINT PRIMARY KEY,

    current_path TEXT NOT NULL,
    current_folder TEXT NOT NULL,

    original_path TEXT NOT NULL,
    original_folder TEXT NOT NULL,

    inventory_path TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_catalog_file_location_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_catalog_file_location_current_path ON catalog_file_location(current_path);
CREATE INDEX ix_catalog_file_location_current_folder ON catalog_file_location(current_folder);
CREATE INDEX ix_catalog_file_location_original_folder ON catalog_file_location(original_folder);
CREATE INDEX ix_catalog_file_location_inventory_path ON catalog_file_location(inventory_path);
CREATE INDEX ix_catalog_file_location_lower_current_path ON catalog_file_location(lower(current_path));
CREATE INDEX ix_catalog_file_location_lower_current_folder ON catalog_file_location(lower(current_folder));

-- ============================================================
-- media_metadata
-- ============================================================
CREATE TABLE media_metadata (
    catalog_file_id BIGINT PRIMARY KEY,
    category VARCHAR(30) NOT NULL,
    subcategory VARCHAR(30) NOT NULL,

    year INTEGER,
    month INTEGER,
    day INTEGER,
    year_month VARCHAR(7),

    capture_date TIMESTAMP,
    date_source VARCHAR(30),

    stored_width INTEGER,
    stored_height INTEGER,

    display_width INTEGER,
    display_height INTEGER,

    orientation_code INTEGER,
    rotation INTEGER,
    orientation_type VARCHAR(30),

    manufacturer VARCHAR(255),
    model VARCHAR(255),

    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,

    metadata_json TEXT,

    CONSTRAINT fk_media_metadata_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_media_metadata_year_month ON media_metadata(year_month);
CREATE INDEX ix_media_metadata_category ON media_metadata(category);
CREATE INDEX ix_media_metadata_subcategory ON media_metadata(subcategory);
CREATE INDEX ix_media_metadata_capture_date ON media_metadata(capture_date);
CREATE INDEX ix_media_metadata_year_month_day ON media_metadata(year, month, day);
CREATE INDEX ix_media_metadata_orientation_type ON media_metadata(orientation_type);
CREATE INDEX ix_media_metadata_rotation ON media_metadata(rotation);
CREATE INDEX ix_media_metadata_stored_dimensions ON media_metadata(stored_width, stored_height);
CREATE INDEX ix_media_metadata_display_dimensions ON media_metadata(display_width, display_height);
CREATE INDEX ix_media_metadata_rebuild_filters ON media_metadata(date_source, capture_date, catalog_file_id);

CREATE INDEX ix_timeline_capture_date_file
    ON media_metadata(capture_date DESC, catalog_file_id DESC)
    WHERE capture_date IS NOT NULL;

-- ============================================================
-- photo
-- ============================================================
CREATE TABLE photo (
    catalog_file_id BIGINT PRIMARY KEY,

    format VARCHAR(50),

    iso INTEGER,
    flash VARCHAR(255),
    exposure_time VARCHAR(80),
    f_number VARCHAR(80),
    focal_length VARCHAR(80),
    lens_model VARCHAR(255),
    white_balance VARCHAR(120),
    exposure_mode VARCHAR(120),
    exposure_program VARCHAR(120),
    metering_mode VARCHAR(120),

    exif_date TIMESTAMP,
    exif_json TEXT,

    CONSTRAINT fk_photo_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_photo_exif_date ON photo(exif_date);
CREATE INDEX ix_photo_iso ON photo(iso);
CREATE INDEX ix_photo_lens_model ON photo(lens_model);

-- ============================================================
-- video
-- ============================================================
CREATE TABLE video (
    catalog_file_id BIGINT PRIMARY KEY,
    container VARCHAR(80),

    video_codec VARCHAR(80),
    audio_codec VARCHAR(80),
    video_profile VARCHAR(120),

    fps DOUBLE PRECISION,

    video_bitrate BIGINT,
    total_bitrate BIGINT,

    duration_seconds DOUBLE PRECISION,

    hdr BOOLEAN NOT NULL DEFAULT FALSE,

    pixel_format VARCHAR(80),
    color_space VARCHAR(80),
    color_transfer VARCHAR(80),
    color_primaries VARCHAR(80),
    bit_depth INTEGER,

    audio_sample_rate INTEGER,
    audio_channels INTEGER,
    audio_channel_layout VARCHAR(80),

    mediainfo_json TEXT,

    CONSTRAINT fk_video_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_video_video_codec ON video(video_codec);
CREATE INDEX ix_video_audio_codec ON video(audio_codec);
CREATE INDEX ix_video_container ON video(container);
CREATE INDEX ix_video_duration_seconds ON video(duration_seconds);
CREATE INDEX ix_video_hdr ON video(hdr);
CREATE INDEX ix_video_pixel_format ON video(pixel_format);
CREATE INDEX ix_video_color_space ON video(color_space);
CREATE INDEX ix_video_color_transfer ON video(color_transfer);
CREATE INDEX ix_video_color_primaries ON video(color_primaries);
CREATE INDEX ix_video_upper_trim_video_codec ON video(upper(trim(video_codec)));

-- ============================================================
-- media_fingerprint
-- ============================================================
CREATE TABLE media_fingerprint (
    id BIGSERIAL PRIMARY KEY,

    catalog_file_id BIGINT NOT NULL,

    kind VARCHAR(30) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,

    sample_index INTEGER NOT NULL DEFAULT 0,
    position_ms BIGINT,

    hash BIGINT,
    hash_bytes BYTEA,
    sample_bytes BYTEA,
    computed_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_media_fingerprint_file
        FOREIGN KEY (catalog_file_id) REFERENCES catalog_file(id) ON DELETE CASCADE,

    CONSTRAINT uk_media_fingerprint UNIQUE (catalog_file_id, kind, algorithm, sample_index),

    CONSTRAINT ck_media_fingerprint_hash_storage
        CHECK ((hash IS NOT NULL AND hash_bytes IS NULL)
            OR (hash IS NULL AND hash_bytes IS NOT NULL)),

    CONSTRAINT ck_media_fingerprint_phash_payload
        CHECK (algorithm <> 'FFMPEG_LANCZOS_PHASH_256_V1'
            OR (hash_bytes IS NOT NULL AND sample_bytes IS NOT NULL
                AND octet_length(hash_bytes) = 32 AND octet_length(sample_bytes) = 1024))
);

CREATE INDEX ix_media_fingerprint_lookup ON media_fingerprint (kind, algorithm);
CREATE INDEX ix_media_fingerprint_file ON media_fingerprint (catalog_file_id);

-- ============================================================
-- fingerprint_failure
-- ============================================================
CREATE TABLE fingerprint_failure (
    id BIGSERIAL PRIMARY KEY,

    catalog_file_id BIGINT NOT NULL,
    kind VARCHAR(30) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,

    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_attempt_at TIMESTAMP,

    CONSTRAINT fk_fingerprint_failure_file
        FOREIGN KEY (catalog_file_id) REFERENCES catalog_file(id) ON DELETE CASCADE,

    CONSTRAINT uk_fingerprint_failure UNIQUE (catalog_file_id, kind, algorithm)
);

CREATE INDEX ix_fingerprint_failure_lookup ON fingerprint_failure (kind, algorithm);

-- ============================================================
-- fingerprint_job_run
-- ============================================================
CREATE TABLE fingerprint_job_run (
    id BIGSERIAL PRIMARY KEY,

    kind VARCHAR(30) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,

    status VARCHAR(30) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,

    processed BIGINT NOT NULL DEFAULT 0,
    failed BIGINT NOT NULL DEFAULT 0,
    total_at_start BIGINT NOT NULL DEFAULT 0,

    message TEXT
);

CREATE INDEX ix_fingerprint_job_run_started ON fingerprint_job_run (started_at DESC);

-- ============================================================
-- timeline (catalog_file partial index)
-- ============================================================
CREATE INDEX ix_timeline_active_visual_file
    ON catalog_file(file_type, id)
    WHERE lifecycle_status = 'ACTIVE'
      AND file_type IN ('PHOTO', 'VIDEO');

-- ============================================================
-- execution
-- ============================================================
CREATE TABLE execution (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,

    execution_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,

    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP,

    source_path TEXT,
    target_path TEXT,

    recursive BOOLEAN NOT NULL DEFAULT FALSE,
    execute_flag BOOLEAN NOT NULL DEFAULT FALSE,

    application_version VARCHAR(50),
    message TEXT,
    message_code VARCHAR(100),
    message_args VARCHAR(2000),
    trigger_event VARCHAR(30),

    files_found INTEGER NOT NULL DEFAULT 0,
    files_analyzed INTEGER NOT NULL DEFAULT 0,
    cache_hits INTEGER NOT NULL DEFAULT 0,
    files_moved INTEGER NOT NULL DEFAULT 0,
    simulated_files INTEGER NOT NULL DEFAULT 0,
    errors INTEGER NOT NULL DEFAULT 0,

    total_expected INTEGER,

    CONSTRAINT uk_execution_public_id UNIQUE (public_id)
);

CREATE INDEX ix_execution_type ON execution(execution_type);
CREATE INDEX ix_execution_status ON execution(status);
CREATE INDEX ix_execution_started_at ON execution(started_at);
CREATE INDEX ix_execution_finished_status ON execution(finished_at, status);

-- ============================================================
-- execution_metrics
-- ============================================================
-- Performance telemetry of a measured processing run (duration, throughput,
-- tuning parameters and photo-hash decode counters). Split from execution: only
-- measured runs have a row, so execution stays free of these mostly-null
-- telemetry columns, and only the telemetry/statistics screen joins this table.
CREATE TABLE execution_metrics (
    execution_id BIGINT PRIMARY KEY,

    duration_millis BIGINT,
    files_per_second DOUBLE PRECISION,

    workers INTEGER,
    chunk_size INTEGER,
    ffmpeg_photo_hash_limit INTEGER,
    ffprobe_video_limit INTEGER,

    photo_hash_jvm_decodable BIGINT,
    photo_hash_ffmpeg_only BIGINT,
    photo_hash_failures BIGINT,

    CONSTRAINT fk_execution_metrics_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE CASCADE
);

-- ============================================================
-- movement
-- ============================================================
CREATE TABLE movement (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,

    execution_id BIGINT NOT NULL,
    catalog_file_id BIGINT,

    source_path TEXT NOT NULL,
    target_path TEXT NOT NULL,

    status VARCHAR(30) NOT NULL,
    reason VARCHAR(30),
    error_message TEXT,

    moved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    undone_at TIMESTAMP,

    CONSTRAINT uk_movement_public_id UNIQUE (public_id),

    CONSTRAINT fk_movement_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_movement_file
        FOREIGN KEY (catalog_file_id)
        REFERENCES catalog_file(id)
        ON DELETE SET NULL
);

CREATE INDEX ix_movement_execution ON movement(execution_id);
CREATE INDEX ix_movement_file ON movement(catalog_file_id);
CREATE INDEX ix_movement_status ON movement(status);
CREATE INDEX ix_movement_reason ON movement(reason);
CREATE INDEX ix_movement_moved_at ON movement(moved_at);
CREATE INDEX ix_movement_target_path ON movement(target_path);

-- ============================================================
-- analysis_error
-- ============================================================
CREATE TABLE analysis_error (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,

    execution_id BIGINT,

    path TEXT NOT NULL,
    error_type VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    error_message TEXT NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_analysis_error_public_id UNIQUE (public_id),

    CONSTRAINT fk_analysis_error_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE SET NULL
);

CREATE INDEX ix_analysis_error_execution ON analysis_error(execution_id);
CREATE INDEX ix_analysis_error_path ON analysis_error(path);
CREATE INDEX ix_analysis_error_created_at ON analysis_error(created_at);
CREATE INDEX ix_analysis_error_type ON analysis_error(error_type);
CREATE INDEX ix_analysis_error_execution_created ON analysis_error(execution_id, created_at);
CREATE INDEX ix_analysis_error_type_path_created ON analysis_error(error_type, path, created_at);
CREATE INDEX ix_analysis_error_lower_path ON analysis_error(lower(path));

-- ============================================================
-- execution_step
-- ============================================================
CREATE TABLE execution_step (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,

    execution_id BIGINT NOT NULL,

    step_type VARCHAR(30) NOT NULL,
    path TEXT,
    message TEXT,
    message_code VARCHAR(100),
    message_args VARCHAR(2000),

    files_found INTEGER,
    files_analyzed INTEGER,
    cache_hits INTEGER,
    errors INTEGER,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_execution_step_public_id UNIQUE (public_id),

    CONSTRAINT fk_execution_step_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_execution_step_execution ON execution_step(execution_id);
CREATE INDEX ix_execution_step_type ON execution_step(step_type);
CREATE INDEX ix_execution_step_created_at ON execution_step(created_at);
CREATE INDEX ix_execution_step_execution_created ON execution_step(execution_id, created_at);

-- ============================================================
-- execution_phase
-- ============================================================
CREATE TABLE execution_phase (
    id BIGSERIAL PRIMARY KEY,

    execution_id BIGINT NOT NULL,

    phase VARCHAR(30) NOT NULL,
    duration_millis BIGINT NOT NULL DEFAULT 0,
    items BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_execution_phase_execution
        FOREIGN KEY (execution_id)
        REFERENCES execution(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_execution_phase_execution ON execution_phase(execution_id);
CREATE INDEX ix_execution_phase_phase ON execution_phase(phase);

-- ============================================================
-- app_user
-- ============================================================
CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(150) NOT NULL,
    role VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    two_factor_secret VARCHAR(64),
    confirmation_token VARCHAR(64),
    confirmation_token_expires_at TIMESTAMP,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'USER'))
);

CREATE INDEX ix_app_user_username ON app_user(username);
CREATE INDEX ix_app_user_enabled ON app_user(enabled);
CREATE INDEX ix_app_user_confirmation_token ON app_user(confirmation_token);

-- ============================================================
-- app_setting
-- ============================================================
CREATE TABLE app_setting (
    id BIGSERIAL PRIMARY KEY,
    setting_key VARCHAR(150) NOT NULL UNIQUE,
    setting_value TEXT,
    value_type VARCHAR(30) NOT NULL,
    description TEXT,
    editable BOOLEAN NOT NULL DEFAULT TRUE,
    created_by_username VARCHAR(100) NOT NULL,
    updated_by_username VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_app_setting_key ON app_setting(setting_key);
CREATE INDEX ix_app_setting_created_by ON app_setting(created_by_username);
CREATE INDEX ix_app_setting_updated_by ON app_setting(updated_by_username);

-- ============================================================
-- user_access_log
-- ============================================================
CREATE TABLE user_access_log (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100),
    event_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    ip_address VARCHAR(100),
    user_agent TEXT,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_user_access_log_username_created ON user_access_log(username, created_at);
CREATE INDEX ix_user_access_log_event_created ON user_access_log(event_type, created_at);

-- ============================================================
-- user_page_preference
-- ============================================================
CREATE TABLE user_page_preference (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    page_key VARCHAR(80) NOT NULL,
    preference_key VARCHAR(80) NOT NULL,
    preference_value TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_page_preference UNIQUE (user_id, page_key, preference_key),
    CONSTRAINT fk_user_page_preference_user
        FOREIGN KEY (user_id)
        REFERENCES app_user(id)
        ON DELETE CASCADE
);

CREATE INDEX ix_user_page_preference_user_page ON user_page_preference(user_id, page_key);

-- ============================================================
-- media_geo_location
-- ============================================================
CREATE TABLE media_geo_location (
    catalog_file_id BIGINT PRIMARY KEY,

    country_code VARCHAR(2),
    country_name VARCHAR(120),
    state_name VARCHAR(120),
    city_name VARCHAR(200),

    distance_km DOUBLE PRECISION,
    confidence VARCHAR(30) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    dataset_version VARCHAR(50),
    resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    manual BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_media_geo_location_media
        FOREIGN KEY (catalog_file_id)
        REFERENCES media_metadata(catalog_file_id)
        ON DELETE CASCADE
);

CREATE INDEX ix_media_geo_location_country ON media_geo_location(country_code);
CREATE INDEX ix_media_geo_location_confidence ON media_geo_location(confidence);
CREATE INDEX ix_media_geo_location_provider ON media_geo_location(provider);

-- ============================================================
-- geo_resolution_cache
-- ============================================================
CREATE TABLE geo_resolution_cache (
    id BIGSERIAL PRIMARY KEY,

    cache_key VARCHAR(50) NOT NULL UNIQUE,

    country_code VARCHAR(2),
    country_name VARCHAR(120),
    state_name VARCHAR(120),
    city_name VARCHAR(200),

    distance_km DOUBLE PRECISION,
    confidence VARCHAR(30) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    dataset_version VARCHAR(50),
    resolved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- geo_admin_boundary
-- ============================================================
CREATE TABLE geo_admin_boundary (
    id BIGSERIAL PRIMARY KEY,

    kind VARCHAR(30) NOT NULL,

    name VARCHAR(200) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    country_name VARCHAR(120),
    state_name VARCHAR(120),

    min_lat DOUBLE PRECISION NOT NULL,
    min_lon DOUBLE PRECISION NOT NULL,
    max_lat DOUBLE PRECISION NOT NULL,
    max_lon DOUBLE PRECISION NOT NULL,

    geometry BYTEA NOT NULL,

    source VARCHAR(40) NOT NULL,
    dataset_version VARCHAR(50) NOT NULL
);

CREATE INDEX ix_geo_admin_boundary_country_kind ON geo_admin_boundary(country_code, kind);
CREATE INDEX ix_geo_admin_boundary_bbox ON geo_admin_boundary(min_lat, max_lat, min_lon, max_lon);

-- ============================================================
-- Map scalability: viewport (bounding-box) filtering
-- ============================================================

-- The media map filters EXIF coordinates by the visible bounding box
-- (latitude/longitude BETWEEN ...) so the payload stays proportional to the
-- viewport instead of returning every pin. This partial index serves that range
-- scan (rows without coordinates are irrelevant to the map).
-- On a large, live database prefer a concurrent index build run outside Flyway.
CREATE INDEX ix_media_metadata_lat_lon ON media_metadata(latitude, longitude)
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL;

-- Administrative pins group coordinate-less media by region; this composite
-- serves that GROUP BY country_code, state_name, city_name.
CREATE INDEX ix_media_geo_location_region ON media_geo_location(country_code, state_name, city_name);

-- ============================================================
-- Duplicate comparison exclusions
-- ============================================================

-- Files and folders the user chose to keep in the catalog but hide from the
-- Duplicados screen (both the exact SHA-256 groups and the visually-similar
-- photos). This is NOT the scan exclusion (nimbus-file-manager.scan.excluded-*): those
-- files stay fully inventoried and visible everywhere else - they are only
-- dropped from duplicate comparison.

-- A single file, kept but never compared. Referenced by public_id (stable across
-- re-inventory in place); the FK cascade removes the exclusion if the file is
-- ever hard-deleted from the catalog.
CREATE TABLE duplicate_file_exclusion (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL,
    created_at TIMESTAMP,
    CONSTRAINT uk_duplicate_file_exclusion_public_id UNIQUE (public_id),
    CONSTRAINT fk_duplicate_file_exclusion_catalog_file
        FOREIGN KEY (public_id) REFERENCES catalog_file(public_id) ON DELETE CASCADE
);

-- A whole folder (by normalized absolute path), recursively: every current and
-- future file at or under this path is hidden from duplicate comparison.
CREATE TABLE duplicate_folder_exclusion (
    id BIGSERIAL PRIMARY KEY,
    folder_path VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP,
    CONSTRAINT uk_duplicate_folder_exclusion_path UNIQUE (folder_path)
);

-- ============================================================
-- USN Change Journal cursor (Windows change source)
-- ============================================================

-- Persisted position in the NTFS USN Change Journal so the Windows change source
-- can catch up on restart instead of rescanning the disk. One row per monitored
-- volume (keyed by the monitored root). journal_id pins the journal instance: if
-- it differs on restart the journal was recreated and the cursor is invalid, so
-- a full reconcile runs. next_usn is advanced only after a batch is fully
-- processed. Values are opaque 64-bit identifiers from Win32 (stored as BIGINT).
CREATE TABLE usn_journal_cursor (
    id BIGSERIAL PRIMARY KEY,
    volume_key VARCHAR(1024) NOT NULL,
    journal_id BIGINT NOT NULL,
    next_usn BIGINT NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_usn_journal_cursor_volume_key UNIQUE (volume_key)
);

-- ============================================================
-- Spring Batch job repository (schema-postgresql.sql)
-- ============================================================
CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;