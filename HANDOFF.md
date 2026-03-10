# HANDOFF.md - Podhome MCP Server

**Last Updated:** 2026-03-10
**Branch:** babashka-rewrite
**Status:** Ready for review/merge

---

## What This Is

A Babashka-based MCP server that wraps the Podhome Integration API. It exposes 17 MCP tools for episode management, chapter/clips CRUD, transcripts, and analytics.

This is a complete rewrite from the original Node.js/TypeScript version.

## Key Files

| File | Purpose |
|------|---------|
| `podhome_mcp.bb` | Main MCP server (Babashka) |
| `bb.edn` | Task runner (run, start, test, health) |
| `flake.nix` | Nix package definition |
| `tests/test_podhome_mcp.clj` | Integration tests |
| `AGENTS.md` | Agent instructions |
| `INCIDENT.md` | Incident report (see below) |

## Quick Commands

```bash
cd /home/wes/src/podhome-mcp

# Run server
PODHOME_API_KEY=your-key bb start

# Run tests
PODHOME_API_KEY=your-key bb test

# Health check
curl http://localhost:9999/health
```

## API Key

- **Current test key:** `rYMcxbcdXJpppYnumePHIBZTrrVZEX` (The Launch show)
- **Config:** `~/.config/podhome/config.edn`
- **API Base:** `https://serve.podhome.fm`

## Important Notes

### INCIDENT: Accidental Test Publish

**DO NOT test against production without guardrails.**

During development, a test episode was accidentally published to the live podcast feed using a public test MP3 (SoundHelix). See `INCIDENT.md` for details.

**Lessons:**
- Add environment validation (test vs production)
- Add confirmation prompts for publish operations
- Consider dry-run mode

### Known Issues

1. **Upload API bug** - The `/api/begin_upload` endpoint returns an error. Might be a Podhome API issue. Use `file_url` in `create-episode` instead for now.

2. **MCP vs REST** - The MCP server at `serve.podhome.fm/api/mcp` doesn't accept `X-API-KEY` header - only OAuth works. This server uses REST API directly (bypasses MCP).

## Architecture

```
Configuration / auth loading
    ↓
HTTP client (api-get, api-post, api-put)
    ↓
Tool implementations (tool-*)
    ↓
Tool registry (tools vector)
    ↓
Tool dispatch (case on name)
    ↓
JSON-RPC handlers
    ↓
HTTP server (http-kit)
    ↓
Entry point (-main)
```

## Tool Testing Status

All 17 tools implemented and tested:

| Tool | Status | Notes |
|------|--------|-------|
| get-show | ✅ | Working |
| list-episodes | ✅ | Working |
| get-episode | ✅ | Working |
| create-episode | ✅ | Working |
| modify-episode | ✅ | Working |
| schedule-episode | ✅ | Fixed field name (episode_id not episodeId) |
| delete-episode | ✅ | Working |
| begin-upload | ⚠️ | Podhome API returns error |
| finalize-upload | ⚠️ | Depends on begin-upload |
| upload-and-create | ⚠️ | Depends on begin-upload |
| create-chapter | ✅ | Working |
| modify-chapter | ✅ | Working |
| delete-chapter | ✅ | Working |
| list-clips | ✅ | Working |
| create-clip | ✅ | Working |
| modify-clip | ✅ | Working |
| delete-clip | ✅ | Working |
| get-transcript | ✅ | Working |
| get-analytics | ✅ | Working |

## Related Repos

- **art19-mcp** - Similar pattern for ART19 API (`/home/wes/src/art19-mcp`)
- **mcp-injector** - MCP client/injector (`/home/wes/src/mcp-injector`)
- **mcptools** - Original Go-based MCP tools (`/home/wes/src/mcptools`)

## What Was Changed

### Deleted (Node.js cruft)
- `package.json`, `package-lock.json`
- `tsconfig.json`, `vitest.config.ts`
- `src/` (TypeScript)
- `scripts/`
- `.env.example`

### Added (Babashka)
- `podhome_mcp.bb` - Single-file MCP server
- `bb.edn` - Task runner
- `tests/test_podhome_mcp.clj` - Integration tests

### Quality
- ✅ cljfmt - All files formatted
- ✅ clj-kondo - 0 errors, 0 warnings
- ✅ Tests pass

## Next Steps

1. Review and merge `babashka-rewrite` branch to `main`
2. Clean up old Node.js files in main branch
3. Add production guardrails (test vs prod detection)
4. Consider adding dry-run mode for publish operations

## Contact

For questions, check:
- `AGENTS.md` - Detailed agent instructions
- `INCIDENT.md` - What NOT to do
- art19-mcp repo for similar patterns
