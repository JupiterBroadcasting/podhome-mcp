# Changelog

All notable changes to this project will be documented in this file.

## [0.1.0] - 2026-03-10

### Added
- Babashka MCP server (complete rewrite from Node.js/TypeScript)
- 17 MCP tools: show, episodes (CRUD), chapters (CRUD), clips (CRUD), transcript, analytics
- Streamable HTTP transport (spec 2025-03-26)
- Session management with Mcp-Session-Id header
- Direct file upload flow (begin-upload, finalize-upload, upload-and-create)
- Nix flake package with NixOS module
- Integration tests (real API, no mocks)

### Known Issues
- `/api/begin_upload` returns error (Podhome API bug — PascalCase vs snake_case)
- Use `create-episode` with `file_url` as workaround
