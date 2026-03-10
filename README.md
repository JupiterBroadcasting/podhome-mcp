# Podhome MCP Server

Babashka Streamable HTTP MCP server for the Podhome Integration API.

## Quick Start

```bash
# Run
bb podhome_mcp.bb

# Or with bb.edn tasks
bb start      # Start on port 9999
bb test       # Run tests
bb health     # Health check
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

## Tools

| Tool | Description |
|------|-------------|
| `get-show` | Get show metadata |
| `list-episodes` | List episodes |
| `get-episode` | Get episode details |
| `create-episode` | Create episode from URL |
| `modify-episode` | Update episode |
| `schedule-episode` | Schedule/publish |
| `delete-episode` | Delete episode |
| `begin-upload` | Start file upload |
| `finalize-upload` | Complete upload |
| `upload-and-create` | Upload + create |
| `create-chapter` | Add chapter |
| `modify-chapter` | Update chapter |
| `delete-chapter` | Delete chapter |
| `list-clips` | List clips |
| `create-clip` | Add clip |
| `modify-clip` | Update clip |
| `delete-clip` | Delete clip |
| `get-transcript` | Get transcript |
| `get-analytics` | Get analytics |

## Nix

```nix
# In flake.nix
inputs.podhome-mcp.url = "github:JupiterBroadcasting/podhome-mcp";
```

## License

MIT
