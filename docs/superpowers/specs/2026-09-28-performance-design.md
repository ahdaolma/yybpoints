# Performance design

## Goal

Reduce avoidable work and peak memory in the LSPosed module without changing genuine ad eligibility, reward handling, audio isolation, or the hidden-display workflow.

## Design

- The production build omits the protocol and network observation hooks in `HookEntry`. The debug build keeps them for troubleshooting. The Activity lifecycle and reward hooks remain in both builds.
- Avatar decoding reads image dimensions first, selects a power-of-two sample size that bounds the decoded bitmap near 256 by 256 pixels, then creates the existing 128 by 128 circular avatar. A decoded avatar is recycled if its Activity was destroyed before delivery.
- The trusted virtual display stays at 180 by 320 pixels and two image buffers. Its raw buffer footprint is under 0.5 MB, while changing its surface strategy risks ad rendering.

## Verification

Build debug and signed release APKs; inspect release bytecode for omitted diagnostic hook registrations; verify release signature and manifest scope; compare idle memory only as a baseline, since no real ad task is available for peak measurement.
