-- Animated WebP stickers were marked UNSUPPORTED_FORMAT because ffmpeg reads no
-- animation. The first frame is now lifted out and decoded, so that verdict is
-- stale: the files are readable and the rows would otherwise sit terminal forever,
-- since an exhausted failure is never fetched again and the manual retry only
-- clears UNKNOWN. Dropping the rows returns them to the pending queue.
--
-- Only WebP is touched, and only that reason: everything else the decoder refused
-- refuses the same way today. A .webp that still fails - a ZIP sticker package,
-- a blank file - is classified again on this pass and goes back to terminal.
DELETE FROM fingerprint_failure f
 USING catalog_file c
 WHERE c.id = f.catalog_file_id
   AND f.reason = 'UNSUPPORTED_FORMAT'
   AND lower(c.extension) = 'webp';