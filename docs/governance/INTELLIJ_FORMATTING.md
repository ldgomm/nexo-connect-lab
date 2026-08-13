# IntelliJ formatting contract

## Canonical toolchain

- Kotlin compiler: `2.4.0`.
- Spotless Gradle plugin: `8.9.0`.
- ktlint engine: `1.8.0` with IntelliJ IDEA style.
- Formatting ratchet: accepted user baseline after `CONNECT.07`, commit
  `558d702bd5e7729721cde71d0e3080513798dcdd`.

Versions are fixed in `gradle/libs.versions.toml`; `.editorconfig` is the shared
source of indentation, line endings, wrapping, trailing-comma and import rules.
Generated output and every `build` directory are outside formatter scope.

## IntelliJ IDEA

1. Enable EditorConfig support and use the project code style.
2. Use `Option+Command+L` for the current file or selected code.
3. Use **Code > Optimize Imports** for the current file when required.
4. Inspect the diff before committing. Do not run a whole-project reformat
   unless a later phase explicitly authorises it.
5. Run `./scripts/verify-formatting-convergence.sh` before the phase gate.

Formatting and import cleanup are non-functional changes only when compilation,
unit tests and executable contracts still pass. Those gates remain authoritative.
If IntelliJ changes behaviour, keep the change uncommitted and run
`./gradlew spotlessApply`; never weaken the tests to accept a formatter change.

## CI and future phase installers

`spotlessApply` must leave canonical sources unchanged on its first pass and its
second pass. `spotlessCheck` then verifies the same pinned rules. The ratchet
formats only Kotlin or Kotlin Gradle files changed after the accepted user
baseline, preventing an unrelated repository-wide rewrite while making new
phase code canonical.

Future phase installers must tolerate formatting-only edits in their preflight,
apply the canonical formatter before tests, and decide acceptance from compiler,
unit and semantic runtime results rather than whitespace or import layout.
