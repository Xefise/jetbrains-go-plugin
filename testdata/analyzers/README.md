Manual check of gopls analyzer diagnostics and their Alt+Enter actions in `./gradlew runIde`.

- `checkme/` — ST1000 (needs enabling), QF1003, S1000, SA4006. No package comment on purpose.
- `checkme/broken/` — a compile error kept in its own package: gopls skips analyzers for a package that doesn't type-check.
