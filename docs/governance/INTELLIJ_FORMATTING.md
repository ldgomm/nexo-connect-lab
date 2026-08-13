# Automated formatting contract

## Canonical toolchain

- Kotlin compiler: `2.4.0`.
- Spotless Gradle plugin: `8.9.0`.
- ktlint engine: `1.8.0` with IntelliJ IDEA style.
- Formatting ratchet: accepted user baseline after `CONNECT.07`, commit
  `558d702bd5e7729721cde71d0e3080513798dcdd`.

Versions are fixed in `gradle/libs.versions.toml`; `.editorconfig` is the shared
source of indentation, line endings, wrapping, trailing-comma and import rules.
Generated output and every `build` directory are outside formatter scope.

## Manual IDE actions

Do not run IntelliJ `Option+Command+L`, **Optimize Imports** or broad cleanup
actions during a phase. Formatting is applied only by the pinned repository
toolchain and verified automatically before the single final phase commit.

## CI and future phase installers

`spotlessApply` must leave canonical sources unchanged on its first pass and its
second pass. `spotlessCheck` then verifies the same pinned rules. The ratchet
formats only Kotlin or Kotlin Gradle files changed after the accepted user
baseline, preventing an unrelated repository-wide rewrite while making new
phase code canonical.

Future phase installers require the accepted clean parent, apply the canonical
formatter as an automated gate, and decide acceptance from compiler, unit and
semantic runtime results rather than whitespace or import layout. Exactly one
commit is created after every phase gate passes.
