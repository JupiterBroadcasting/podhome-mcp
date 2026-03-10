# AGENTS.md — podhome-mcp

> "Simple is the opposite of complex. Easy is the opposite of hard."
> — Rich Hickey

## What This Is

A Babashka Streamable HTTP MCP server wrapping the Podhome Integration API.

**Transport:** MCP Streamable HTTP (spec `2025-03-26`)
**Endpoint:** Single `/mcp` POST + `/health`
**Compatible with:** mcp-injector `{:podhome {:url "http://127.0.0.1:PORT/mcp"}}`

## Project Structure

```
podhome-mcp/
├── podhome_mcp.bb      # Single-file MCP server (the whole thing)
├── bb.edn              # Tasks: run, start, test, health
├── flake.nix           # Nix package
├── README.md           # Usage docs
├── tests/
│   └── test_podhome_mcp.clj  # Integration tests
└── AGENTS.md          # This file
```

## Philosophy

Same as art19-mcp and mcp-injector:

- **Test-first** — tests guide development
- **Integration tests only** — real API, no mocks
- **Clean lint** — no warnings
- **One file is fine** — don't split until needed
- **YAGNI** — don't build for imagined futures

## Running

```bash
# Dev — OS-assigned port
bb run

# Dev — fixed port (or PODHOME_MCP_PORT)
bb start

# Health check
bb health

# Tests
bb test
```

Auth via env vars or `~/.config/podhome/config.edn`:

```bash
export PODHOME_API_KEY="your-key"
```

```clojure
;; ~/.config/podhome/config.edn
{:api-key "your-key"}
```

## Tools

| Tool | Description |
|------|-------------|
| `get-show` | Get show metadata |
| `list-episodes` | List episodes (filter by status) |
| `get-episode` | Get episode details |
| `create-episode` | Create episode from URL |
| `modify-episode` | Update episode metadata |
| `schedule-episode` | Schedule or publish |
| `begin-upload` | Start file upload |
| `finalize-upload` | Complete file upload |
| `upload-and-create` | Upload file + create episode |
| `delete-episode` | Delete episode |
| `create-chapter` / `modify-chapter` / `delete-chapter` | Chapter CRUD |
| `list-clips` / `create-clip` / `modify-clip` / `delete-clip` | Clip CRUD |
| `get-transcript` | Get episode transcript |
| `get-analytics` | Get download analytics |

## Architecture

Same structure as art19-mcp:

```
Configuration / auth loading
    ↓
Podhome HTTP client (api-get, api-post, api-put)
    ↓
Tool implementations (tool-*)
    ↓
Tool registry (tools vector)
    ↓
Tool dispatch (case on name → tool-*)
    ↓
JSON-RPC handlers
    ↓
HTTP server (http-kit)
    ↓
Entry point (-main)
```

## Adding a Tool

1. Write `tool-<name> [args]` function
2. Add entry to `tools` vector
3. Add case branch in `dispatch-tool`
4. Add test

## Testing

```bash
# Run integration tests (real API)
bb test
```

Tests call the real Podhome API - no mocks.

## Common Gotchas

- **Port 0 allocation:** Default is OS-assigned port. Use `PODHOME_MCP_PORT` for fixed port.
- **API base URL:** Override with `PODHOME_API_BASE` env var or `:api-base` in config.
- **Session validation:** Server validates session on non-initialize requests.
