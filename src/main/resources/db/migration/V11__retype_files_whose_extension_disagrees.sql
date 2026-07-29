-- Re-types files the catalog still calls media although their extension says
-- otherwise. A rename used to update name and extension without re-typing, so a
-- file corrected from .bmp to .mps stayed PHOTO: counted on every media screen and
-- queued for a fingerprint it can never have. The code no longer leaves the type
-- behind; these are the rows written before it.
--
-- Only media types are touched, and only towards what the extension says. A row
-- typed PDF or WORD came from the mime type and is harmless where it is; a media
-- one is not, because it drives the photo and video pipelines.
--
-- The extension lists mirror FileType at the time of this migration. That is a
-- snapshot, not a second source of truth: a migration records what was repaired
-- on a given day, and the enum stays the only rule the running code reads.
UPDATE catalog_file
   SET file_type = CASE
           WHEN lower(extension) IN ('jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'heic', 'heif', 'tif', 'tiff')
               THEN 'PHOTO'
           WHEN lower(extension) IN ('mp4', 'mov', 'avi', 'mkv', 'wmv', 'flv', 'webm', 'mpeg', 'mpg', '3gp', 'm4v')
               THEN 'VIDEO'
           WHEN lower(extension) IN ('mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'wma', 'opus', 'amr')
               THEN 'AUDIO'
           WHEN lower(extension) = 'pdf' THEN 'PDF'
           WHEN lower(extension) IN ('doc', 'docx') THEN 'WORD'
           WHEN lower(extension) IN ('xls', 'xlsx', 'csv') THEN 'EXCEL'
           WHEN lower(extension) IN ('ppt', 'pptx') THEN 'POWERPOINT'
           WHEN lower(extension) IN ('txt', 'md', 'log', 'json', 'xml', 'yaml', 'yml') THEN 'TEXT'
           WHEN lower(extension) = 'zip' THEN 'ZIP'
           WHEN lower(extension) = 'rar' THEN 'RAR'
           WHEN lower(extension) = '7z' THEN 'SEVEN_Z'
           ELSE 'OTHER'
       END
 WHERE file_type IN ('PHOTO', 'VIDEO', 'AUDIO')
   AND lower(coalesce(extension, '')) NOT IN ('jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'heic', 'heif', 'tif',
       'tiff', 'mp4', 'mov', 'avi', 'mkv', 'wmv', 'flv', 'webm', 'mpeg', 'mpg', '3gp', 'm4v', 'mp3', 'wav', 'flac',
       'aac', 'ogg', 'm4a', 'wma', 'opus', 'amr');

-- A file that is no longer media cannot have a visual fingerprint, and the failure
-- recorded against it only says it was asked for the wrong thing.
DELETE FROM fingerprint_failure f
 USING catalog_file c
 WHERE c.id = f.catalog_file_id
   AND c.file_type NOT IN ('PHOTO', 'VIDEO');