# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- `get-chapters` tool — list chapters for an episode (paginated)

### Changed
- `modify-episode`: added `alternate_enclosure_url` and `alternate_enclosure_type` fields (add video/alt audio to existing episode)
- `begin-upload`: added optional fields: `description`, `link`, `publish_date`, `enhance_audio`. Marked as broken (returns HTTP 400).
- `finalize-upload`: added optional fields: `suggest_chapters`, `suggest_details`, `suggest_clips`
- `list-episodes`: paginated with slim defaults (20 episodes, 7 fields). Use `fields="full"` for all data. Returns `total`, `offset`, `limit`, `has_more`.
- `list-clips`: paginated (default 50, max 100). Returns `total`, `offset`, `limit`, `has_more`.
- Tool count: now 20 tools (was 19)

### Fixed
- `begin-upload` bug: confirmed HTTP 400 with .NET error `'object' does not contain a definition for 'UploadUrl'`. Root cause: PascalCase/snake_case mismatch in server C# code.

## [0.1.0] - 2026-03-10

### Added
- Babashka MCP server (complete rewrite from Node.js/TypeScript)
- 20 MCP tools: show, episodes (CRUD), chapters (CRUD), clips (CRUD), transcript, analytics
- Streamable HTTP transport (spec 2025-03-26)
- Session management with Mcp-Session-Id header
- Direct file upload flow (begin-upload, finalize-upload, upload-and-create)
- Nix flake package with NixOS module
- Integration tests (real API, no mocks)
- Smoke test (`bb smoke`) — no API key needed

### Known Issues
- `/api/begin_upload` returns error (Podhome API bug — PascalCase vs snake_case)
- Use `create-episode` with `file_url` as workaround
