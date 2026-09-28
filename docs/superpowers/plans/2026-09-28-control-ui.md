# Control page refinement

Approved direction: Material-style Android interaction, restrained typography and grouped settings. UI-only scope.

Palette: background #F7F9F8, white surface, ink #202C29, muted #63716C, teal #087D68, pale teal #E8F2ED.
Typography: platform sans, title 28sp, section 18sp, body 15sp, supporting 13sp. No new font dependency.
Layout: title and compact account; unified pale status/result panel; grouped schedule and history; one fixed primary action; quiet uninstall action.
Motion: native bounded ripple, short press response, 200ms detail expansion, state-label fade. Respect disabled system animations. No idle animation.

## Implementation
- [x] Refine ControlActivity layout and visual helpers; retain existing task and reward logic.
- [x] Present active/idle action in one stable location and keep stop accessible during an active session.
- [x] Build signed candidate; inspect the control screen through ADB without starting advertising tasks.
- [x] Save local APK and screenshot; report verification limits. No publication.

Verification: signed release build and lintVitalRelease passed. Installed to user 0 through ADB. Screenshot inspected; fixed initial scroll caused by button focus. Live active-state transitions and reward completion were not exercised in this UI-only pass. APK signature verification succeeded.
