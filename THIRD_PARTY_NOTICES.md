# Third-party notices

This project contains code modified from the following sources. Each ported file carries a
`Modified from <source>` header.

## go-language-helper

- Source: https://github.com/cag11/go-language-helper
- Commit: 556c2747c37519daabfd6ffdb67cefa55622e799
- Licence: Apache License 2.0 — full text in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
- Copyright: cag11, github.com/cag11/go-language-helper (the upstream `LICENSE` leaves the copyright line empty)
- Original files (under `src/main/kotlin/io/github/cag11/golang/` unless noted):
  `lexer/GoLexer.kt`, `psi/GoTokenTypes.kt`, `psi/GoPsi.kt`, `lang/GoDialect.kt`, `lang/GoFileTypes.kt`,
  `highlight/GoSyntaxHighlighter.kt`, `highlight/GoColorSettingsPage.kt`, `editor/GoEditorSupport.kt`,
  `src/test/kotlin/io/github/cag11/golang/lexer/GoLexerTest.kt`, extension registrations in
  `src/main/resources/META-INF/plugin.xml`
- Mapping to modified files in this project (under `src/main/kotlin/io/github/xefise/golsp/lang/`
  unless noted):
  - `lexer/GoLexer.kt` → `GoLexer.kt`
  - `psi/GoTokenTypes.kt` → `GoTokenTypes.kt`
  - `psi/GoPsi.kt` → `GoParserDefinition.kt`, `GoModParserDefinitions.kt`
  - `lang/GoDialect.kt` → keyword sets in `GoLexer.kt` and `GoModLexer.kt`, languages in `GoModLanguages.kt`
  - `lang/GoFileTypes.kt` → `GoModLanguages.kt`
  - `highlight/GoSyntaxHighlighter.kt` → `GoSyntaxHighlighter.kt`
  - `highlight/GoColorSettingsPage.kt` → `GoColorSettingsPage.kt`
  - `editor/GoEditorSupport.kt` → `GoEditorSupport.kt`
  - `GoLexerTest.kt` → `src/test/kotlin/io/github/xefise/golsp/lang/GoLexerTest.kt`
  - extension registrations → `src/main/resources/META-INF/plugin.xml`
- Written from scratch, not derived from this source: `GoLanguage.kt`, `GoFileType.kt`, `GoFile.kt`,
  `GoFlatParser.kt`, `GoPsiElements.kt`, `model/GoStringLiterals.kt`.
- Changes: package and identifiers renamed; language, file type and colour key IDs replaced; lexer number,
  escape, Unicode-identifier and BOM handling rewritten; go.mod/go.work lexed by a separate word lexer.

## go-lang-idea-plugin

- Source: https://github.com/go-lang-plugin-org/go-lang-idea-plugin
- Commit: e73dab44213ffa76b3d6a853aecb109929c3e2b5
- Licence: Apache License 2.0 — full text in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
- Copyright: Copyright 2013-2016 Sergey Ignatov, Alexander Zolotov, Florin Patan
- Original files: `src/com/goide/runconfig/GoConsoleFilter.java`
- Mapping to modified files in this project (under `src/main/kotlin/io/github/xefise/golsp/run/`):
  - `GoConsoleFilter.java` → `GoConsoleFilter.kt` (path resolution), `model/GoConsoleLinks.kt` (line matching)
- Changes: ported to Kotlin; matching split into a platform-free model; App Engine paths, GOPATH/import-path
  resolution and `go get` links removed; resolution against the run's working and package directories added;
  URLs rejected; files missing from VFS linked lazily instead of a synchronous refresh; the index fallback
  limited to project scope and skipped in dumb mode.

## vscode-go

- Source: https://github.com/golang/vscode-go
- Commit: 1608cb5c907bfb238967d9aceb507620b08cf2a3
- Licence: MIT — full text in [`licenses/MIT-vscode-go.txt`](licenses/MIT-vscode-go.txt)
- Copyright: Original Work Copyright (c) 2015-2020 Microsoft Corporation; Current Work and Modifications
  Copyright (c) 2020-present The Go Authors
- Original files: `extension/src/utils/envUtils.ts` (`parseEnvFile`, `substituteEnvVars`)
- Mapping to modified files in this project:
  - `extension/src/utils/envUtils.ts` →
    `src/main/kotlin/io/github/xefise/golsp/execution/model/DotEnvParser.kt`
- Changes: ported to Kotlin; parses text instead of reading a file (file reading and its errors moved to
  `execution/GoEnvFileLoader.kt`, written from scratch).
