# Changelog

All notable changes to this fork are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This fork is based on [MCRcortex/nvidium](https://github.com/MCRcortex/nvidium); only changes made in this fork are
listed below.

## [Unreleased]

### Added
- Stonecutter multi-version layout for building across Sodium generations (G0.4–G0.8) from a single source tree.
- Iris integration groundwork: feasibility/staged design doc and an S1 state facade.
- GitHub Actions CI and an auto-tag / auto-release workflow.
- Repository documentation: README, CONTRIBUTING, SECURITY, issue/PR templates.

### Changed
- Refocused the tree on **1.20.1 (G0.4, Sodium 0.4.10)** as the canonical base.
- Updated dependencies to the latest 1.20.1-compatible versions.

### Fixed
- Fall back to defaults on a corrupt config instead of crashing.
- Write the config atomically to avoid corruption on a crash.
- Guard region capacity to prevent an out-of-bounds crash.
- Fix a render-thread crash, page over-commit, and stale region visibility.
- Fail loud on oversized allocator requests instead of corrupting memory silently.
- Remove dead, non-null-safe `checkIrisShaders()`.

### Performance
- O(1) player-region marking instead of scanning all visible regions.
- Drop per-chunk and per-frame allocations from the upload and render hot paths.
- Batch region-meta uploads to once per frame.
- Always free VRAM when over budget at high render distance.

[Unreleased]: https://github.com/denfry/nvidium/commits/multiversion
