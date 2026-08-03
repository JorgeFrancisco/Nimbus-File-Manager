-- The ffmpeg/ffprobe paths, the auto-install switch and the download url stop
-- being catalog data. Stored in app_setting they travelled inside a backup and
-- landed describing another machine, which is how every ffprobe call failed for
-- seventeen hours against a folder that only existed on the installation the
-- backup came from. The location is now owned by the application
-- (<workspace>/tools/ffmpeg/bin) and the url is a property, the same way the
-- embedded PostgreSQL already works.
--
-- Nothing is carried over: these rows describe where binaries live, and the
-- answer is recomputed on this machine at every start. A value pinned by hand
-- is deliberately dropped rather than migrated - keeping it would reinstate the
-- very pointer this removes.
DELETE FROM app_setting
 WHERE setting_key IN ('nimbus-file-manager.tools.ffmpeg',
                       'nimbus-file-manager.tools.ffprobe',
                       'nimbus-file-manager.tools.auto-install',
                       'nimbus-file-manager.tools.download-url');