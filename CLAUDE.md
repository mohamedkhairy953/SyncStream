# Repository rules

## Keep the README up to date on every merge to `master`

Whenever changes are merged into `master`, update `README.md` in the **same** merge so it always
reflects the current state of the project. Before completing a merge to `master`, review whether
the change affects any of:

- the module architecture or dependency graph,
- the tech stack or dependency versions,
- build/run instructions,
- user-facing behavior or features.

If it does, update the corresponding `README.md` section as part of the merge. If it genuinely
does not (e.g. a pure refactor with no external effect), the README needs no change — but the
check is mandatory, not optional.

> Note: this is a documented process rule, not an automated hook — there is no merge event that can
> author meaningful README content on its own. The rule is enforced by following it during merges.

# Project overview

SyncStream is a multi-module Android app for synchronized LAN video playback (master streams to
clients over WebRTC). See `README.md` for the architecture and `CONTRACTS.md` for the binding
public-API contract of every module.

## Build commands

```bash
./gradlew assembleDebug          # build all modules + debug APK
./gradlew :app:installDebug      # install on a connected device
```

## Module conventions

- Module Gradle config lives in `build-logic` convention plugins
  (`syncstream.android.library` / `.compose` / `.application`); apply those rather than repeating
  `android {}` config in module build files.
- Dependency direction is acyclic: `:app` → features → capability libs → `:core`. Do not
  introduce edges that point back up the graph.
- Expose a dependency as `api` only when its types appear in a module's public API (e.g. `:core`
  api-exports WebRTC because `AppContainer` returns `PeerConnectionFactory`); otherwise use
  `implementation`.
- Source file packages follow `CONTRACTS.md`; a module's Gradle `namespace` need not match its
  file packages.
