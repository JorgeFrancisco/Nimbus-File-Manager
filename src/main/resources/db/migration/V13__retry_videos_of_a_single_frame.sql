-- Videos of a single frame - the clip a phone keeps beside a photo, or a recording
-- stopped the instant it started - were marked UNSUPPORTED_FORMAT. Nothing about
-- them is unsupported: the sampler asks for frames at timestamps spread over the
-- duration, a 0.04 second video holds none of them, and ffmpeg returned nothing.
--
-- The one frame is now hashed the way a photo is, so the verdict is stale and the
-- rows would otherwise sit terminal forever: an exhausted failure is never fetched
-- again, and the manual retry only clears UNKNOWN.
--
-- A video that still cannot be decoded is classified again on this pass and goes
-- back to terminal, so nothing is retried forever.
DELETE FROM fingerprint_failure
 WHERE kind = 'VIDEO_PHASH' AND reason = 'UNSUPPORTED_FORMAT';