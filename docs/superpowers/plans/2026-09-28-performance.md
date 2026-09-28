# Performance Optimization Implementation Plan

> **For agentic workers:** Execute these tasks inline in this session. The user approved the design on 2026-09-28.

**Goal:** Remove release-only diagnostic overhead and bound avatar decode memory.

**Architecture:** Use `BuildConfig.DEBUG` guards around diagnostic hook installation. Keep functional hooks unchanged. Use Android bitmap bounds decoding and power-of-two sampling.

**Tech Stack:** Java 17, Android SDK 34, Gradle 8.7, LSPosed/Xposed API.

---

### Task 1: Release hook gating

**Files:** `app/src/main/java/com/codex/yybpoints/HookEntry.java`

- [x] Wrap protocol and network diagnosis hook installation in compile-time `BuildConfig.DEBUG` branches.
- [x] Confirm Activity lifecycle and reward hooks remain outside those branches.
- [x] Build release and debug variants; inspect release Java bytecode for diagnostic registrations.

### Task 2: Avatar decoding

**Files:** `app/src/main/java/com/codex/yybpoints/AvatarLoader.java`

- [x] Decode bounds, choose sample size, and decode the scaled bitmap.
- [x] Recycle bitmap when asynchronous delivery finds a destroyed Activity.
- [x] Build both variants and verify existing avatar sizing path in source.

### Task 3: Release checks

**Files:** `README.md`

- [x] Document the measured idle PSS and limitations of the measurement.
- [x] Verify signed APK package, signature, and recommended scope.
