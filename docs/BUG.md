# Bug: `begin_upload` Returns 400 — .NET PascalCase Deserialization Mismatch

**Date:** 2026-03-10 (original), 2026-04-01 (confirmed)
**API Endpoint:** `POST /api/begin_upload`
**Severity:** High — blocks direct file upload flow entirely

## Description

The `/api/begin_upload` endpoint returns HTTP 400 with a .NET runtime error instead of a presigned upload URL. The error is a server-side C# deserialization bug — the code expects a property named `UploadUrl` (PascalCase) but the JSON serializer produces `upload_url` (snake_case).

## Confirmed Reproduction

```bash
curl -X POST "https://serve.podhome.fm/api/begin_upload" \
  -H "X-API-KEY: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "My New Episode",
    "file_name": "test.mp3",
    "file_size": 52428800
  }'
```

**Response:**
```
HTTP/2 400
x-powered-by: ASP.NET

Something went wrong preparing the upload: 'object' does not contain a definition for 'UploadUrl'
```

## Root Cause

The error message `'object' does not contain a definition for 'UploadUrl'` is a classic .NET runtime error. The server's C# code is trying to access `.UploadUrl` on a deserialized object that doesn't have that property. The OpenAPI spec defines the response field as `upload_url` (snake_case), which is what the JSON serializer produces, but the C# code expects PascalCase.

The `additionalProperties: false` constraint on the request schema means there is no client-side workaround — we cannot send `UploadUrl` in the request body.

## Impact

- **Direct file upload flow is completely broken** — `begin-upload`, `finalize-upload`, and `upload-and-create` tools cannot be used
- **Workaround exists:** Use `create-episode` with `file_url` pointing to a publicly accessible audio URL
- **No orphan resources:** The 400 response means no draft episode is created

## Workaround

```bash
curl -X POST "https://serve.podhome.fm/api/createepisode" \
  -H "X-API-KEY: YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "My Episode",
    "file_url": "https://example.com/audio.mp3"
  }'
```

## Suggested Fix (for Podhome)

Ensure the C# model property matches the JSON serialization convention. Either:
1. Add `[JsonPropertyName("upload_url")]` to the `UploadUrl` property, or
2. Configure the JSON serializer to use PascalCase consistently

## Status

Confirmed live as of 2026-04-01. Awaiting upstream fix.
