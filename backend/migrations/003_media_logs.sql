-- Allow "logs" as a media type so the upload_logs remote command has somewhere to land.
ALTER TABLE media_uploads DROP CONSTRAINT IF EXISTS media_uploads_media_type_check;
ALTER TABLE media_uploads ADD CONSTRAINT media_uploads_media_type_check
    CHECK (media_type IN ('audio', 'video', 'screenshot', 'logs'));
