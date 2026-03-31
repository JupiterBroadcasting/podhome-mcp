# Podhome MCP Server

Babashka Streamable HTTP MCP server for the Podhome Integration API.

## Quick Start

```bash
# Run (OS-assigned port)
PODHOME_API_KEY=your-key bb podhome_mcp.clj

# Or use bb.edn tasks
bb start     # Start on port 9999
bb test      # Run tests
bb health    # Health check
```

## Configuration

Set via environment variable:
```bash
export PODHOME_API_KEY="your-key"
```

Or via config file `~/.config/podhome/config.edn`:
```clojure
{:api-key "your-key"}
```

Override API base URL:
```bash
export PODHOME_API_BASE="https://serve.podhome.fm"
```

Override host/port:
```bash
export PODHOME_MCP_HOST="0.0.0.0"
export PODHOME_MCP_PORT="3003"
```

## Available Tools (19)

| Tool | Status | Description |
|------|--------|-------------|
| `get-show` | ✅ | Get show metadata |
| `list-episodes` | ✅ | List episodes |
| `get-episode` | ✅ | Get episode details |
| `create-episode` | ✅ | Create episode from URL |
| `modify-episode` | ✅ | Update episode |
| `schedule-episode` | ✅ | Schedule/publish |
| `delete-episode` | ✅ | Delete episode |
| `begin-upload` | ⚠️ | Start file upload (API bug — see Known Issues) |
| `finalize-upload` | ⚠️ | Complete upload (depends on begin-upload) |
| `upload-and-create` | ⚠️ | Upload + create episode (depends on begin-upload) |
| `create-chapter` | ✅ | Add chapter |
| `modify-chapter` | ✅ | Update chapter |
| `delete-chapter` | ✅ | Delete chapter |
| `list-clips` | ✅ | List clips |
| `create-clip` | ✅ | Add clip |
| `modify-clip` | ✅ | Update clip |
| `delete-clip` | ✅ | Delete clip |
| `get-transcript` | ✅ | Get transcript |
| `get-analytics` | ✅ | Get analytics |

## Nix

### Run directly
```bash
nix run github:JupiterBroadcasting/podhome-mcp
```

### NixOS Module
```nix
services.podhome-mcp = {
  enable = true;
  port = 3003;
  host = "127.0.0.1";
  apiKeyFile = "/run/secrets/podhome-api-key";
};
```

### Dev shell
```bash
nix develop
```

## Testing

```bash
PODHOME_API_KEY=your-key bb test
```

Tests call the real Podhome API — no mocks.

## Known Issues

- **Upload API bug:** `/api/begin_upload` returns a 500 error due to a PascalCase vs snake_case mismatch in the Podhome API. Use `create-episode` with `file_url` as a workaround.

- **MCP endpoint auth:** The MCP server at `serve.podhome.fm/api/mcp` only accepts OAuth, not `X-API-KEY`. This server uses the REST API directly.

## License

MIT
