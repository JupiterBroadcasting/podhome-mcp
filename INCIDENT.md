# Incident Report: Accidental Test Episode Publish

**Date:** 2026-03-10
**Severity:** Medium
**Status:** Resolved

## What Happened

During development of the podhome-mcp Babashka server, a test episode was accidentally published to the live Podhome production environment using a publicly available test MP3 file.

## Timeline

1. Testing episode creation with the new `podhome_mcp.clj` (Babashka MCP server)
2. Created episode with title "Test Episode from CLI" using a public test audio URL: `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3`
3. Episode was created as draft
4. Later ran schedule command with `--publish-now` flag
5. Episode was published to the live podcast feed

## Root Cause

- Test code was running against the real Podhome API (serve.podhome.fm)
- No safeguards preventing accidental publishing to production
- The test workflow was indistinguishable from normal operation

## Impact

- A test episode was published to the live "The Launch" podcast feed
- Used placeholder audio (SoundHelix test file)
- Episode has since been unpublished/deleted

## Lessons Learned

1. **Never test against production** - Should have been using a test/staging environment
2. **Add guardrails** - Test scripts should have explicit confirmation before publish operations
3. **Clear distinction** - Test vs production should be obvious in code/config

## Action Items

- [ ] Add environment validation (test vs production)
- [ ] Add confirmation prompts for publish operations in test code
- [ ] Consider adding dry-run mode to tools
- [ ] Document test audio sources that are safe to use
- [ ] Review all tools for destructive operations

## Safe Test Audio Sources

For future testing, use these public test files:
- `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3`
- `https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3`

Note: These are legal to use for testing but should NEVER be published.
