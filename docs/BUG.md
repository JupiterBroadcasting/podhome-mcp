# Bug Report: begin_upload API Returns Error

**Date:** 2026-03-10
**API Endpoint:** `POST /api/begin_upload`
**Severity:** Medium

## Description

The `/api/begin_upload` endpoint returns a 500 error instead of a successful response with presigned upload URL.

## Steps to Reproduce

```bash
curl -X POST "https://serve.podhome.fm/api/begin_upload" \
  -H "X-API-KEY: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Test Episode",
    "file_name": "test.mp3",
    "file_size": 52428800
  }'
```

## Expected Response (per OpenAPI spec)

```json
{
  "upload_url": "https://podhomestorage.blob.core.windows.net/episodes/...mp3?sv=...",
  "blob_name": "638789012345678901234episode-guid.mp3",
  "episode_id": "123e4567-e89b-12d3-a456-426614174000"
}
```

## Actual Response

```
Something went wrong preparing the upload: 'object' does not contain a definition for 'UploadUrl'
```

## Root Cause

The server code appears to expect `UploadUrl` (PascalCase) but the spec and proper JSON response uses `upload_url` (snake_case).

## Impact

- Cannot use direct upload flow
- Workaround: Use `create-episode` with `file_url` pointing to a publicly accessible audio URL instead

## Workaround

Use `create-episode` with a public URL:

```bash
curl -X POST "https://serve.podhome.fm/api/createepisode" \
  -H "X-API-KEY: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "My Episode",
    "file_url": "https://example.com/audio.mp3"
  }'
```

## Spec Reference

OpenAPI spec at `/api/docs/integration-v1/openapi.json` shows the response should use snake_case:

```json
"example": {
  "upload_url": "https://podhomestorage.blob.core.windows.net/...",
  "blob_name": "...",
  "episode_id": "..."
}
```

## Suggested Fix

Ensure the server returns `upload_url` (snake_case) matching the OpenAPI spec, or update the code to accept both formats.
