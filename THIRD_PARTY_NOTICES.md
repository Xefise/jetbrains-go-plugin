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
- Mapping to modified files in this project (under `src/main/kotlin/io/github/xefise/golsp/execution/`):
  - `GoConsoleFilter.java` → `GoConsoleFilter.kt` (path resolution), `model/GoConsoleLinks.kt` (line matching)
- Changes: ported to Kotlin; matching split into a platform-free model; App Engine paths, GOPATH/import-path
  resolution and `go get` links removed; resolution against the run's working and package directories added;
  URLs rejected; files missing from VFS linked lazily instead of a synchronous refresh; the index fallback
  limited to project scope and skipped in dumb mode.
- Also ported (same commit and licence): `src/com/goide/runconfig/testing/coverage/GoCoverageRunner.java`
  (`parseCoverage`: profile line format, block-to-line expansion, partial lines, `out` data file extension) →
  `src/main/kotlin/io/github/xefise/golsp/coverage/model/GoCoverProfile.kt`,
  `src/main/kotlin/io/github/xefise/golsp/coverage/GoCoverageRunner.kt`. Changes: ported to Kotlin; parsing split
  into a platform-free model; duplicate blocks merged per profile mode; statement-less blocks skipped; a line
  takes the best count of its blocks instead of their sum; import paths resolved from go.mod/go.work text instead
  of the Go plugin's package index; absolute and `_/` file names accepted.
- Also ported (same commit and licence): `resources/liveTemplates/go.xml` (template names `main`, `init`, `meth`,
  `test`, `bench`, `for`, `forr`, `type`, `err` and their texts) → `src/main/resources/liveTemplates/Go.xml`. Changes:
  own context types, variable defaults and receiver expression; `err` returns; `bench` uses `b.Loop()`; `for`/`forr`
  wrap a selection; tag, `printf`, `map`, `:`, `imp`, `p`, `con`, `iota` templates dropped.

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
- Also ported (same commit and licence): `extension/src/goTools.ts` (`getImportPathWithVersion`: gopls and dlv
  pins), `extension/src/goToolsInformation.ts` (gopls and dlv import and module paths), `extension/src/goEnv.ts`
  (`toolInstallationEnvironment`), `extension/src/goInstallTools.ts` (`installTools`: the `go<version>+auto`
  GOTOOLCHAIN rule; `tmpDirForToolInstallation`: installing from a neutral directory), `extension/src/util.ts`
  (`GoVersion`: devel builds assumed newer than releases) → `src/main/kotlin/io/github/xefise/golsp/toolchain/model/`
  `GoToolRegistry.kt`, `GoToolInstall.kt`, `GoVersion.kt`. Changes: ported to Kotlin as data-only descriptions
  (no process launch); dlv pins chosen by the project's Go from Delve's supported-version ranges, extended past
  go1.21; Go prereleases ordered before their release; GOPATH/GOBIN overrides and the minimum-Go refusal dropped.
- Also ported (same commit and licence): `extension/snippets/go.json` (snippet texts of `func`, `var`, `const`,
  `switch`, `sel`, `for`, `forr`, `if`, `ie`, `fp`, `ff`, `lf`, `go`, `meth`, `tf`, `bf`, `tys`, `tyi`) →
  `src/main/resources/liveTemplates/Go.xml`. Changes: converted to IntelliJ live templates with own names (`sw`, `ife`,
  `st`, `in`, `test`, `bench`), contexts and variables; `if`/`ife`/`for`/`fori`/`forr`/`go`/`def` wrap a selection.
- Written from scratch, not derived from vscode-go: `toolchain/model/GoBinaryInfo.kt`, `ModuleVersion.kt`,
  `GoToolVersionCheck.kt`.

## Delve

- Source: https://github.com/go-delve/delve, `pkg/goversion/compat.go` at each release tag v1.20.0 … v1.27.0
- Licence: MIT; Copyright (c) 2014 Derek Parker
- Data only, no code copied: the minimum and maximum Go minor accepted by each Delve release line, as the table
  in `src/main/kotlin/io/github/xefise/golsp/toolchain/model/GoToolRegistry.kt` (`DelveSupport`).

## Go

- Source: https://github.com/golang/go
- Version: go1.27.3
- Licence: BSD 3-Clause — full text in [`licenses/BSD-3-Clause-Go.txt`](licenses/BSD-3-Clause-Go.txt)
- Copyright: Copyright 2009 The Go Authors
- Original files: `src/testing/match.go` (`rewrite`, `isSpace`)
- Mapping to modified files in this project:
  - `src/testing/match.go` →
    `src/main/kotlin/io/github/xefise/golsp/test/model/GoTestRunPattern.kt` (`rewrite`, `isSpace`)
- Changes: ported to Kotlin; `strconv.IsPrint` approximated with Java Unicode categories, `strconv.QuoteRune`
  reduced to the escapes non-printable runes need.
- Also ported (tag go1.27.3, commit 2aae1000ea1d4b28923b96db28acf4e3b99d6aff; Copyright 2017 The Go Authors):
  `src/cmd/internal/test2json/test2json.go` (`Converter`, `lineBuffer`,
  `isBenchmarkName`, `trimUTF8`) and the `ok`/`FAIL` summary of `runTestActor` in `src/cmd/go/internal/test/test.go` →
  `src/main/kotlin/io/github/xefise/golsp/test/model/GoTest2Json.kt`, `GoTest2JsonLineBuffer.kt`; test data
  `src/cmd/internal/test2json/testdata/*.test`, `*.json` copied unedited to `src/test/testData/test2json/golden/`;
  tests from `src/cmd/internal/test2json/test2json_test.go` (`TestGolden` chunkings and `diffJSON` →
  `src/test/kotlin/io/github/xefise/golsp/test/model/GoTest2JsonGoldenTest.kt`, `TestTrimUTF8` → `GoTest2JsonTest.kt`).
  Changes: ported to Kotlin, events go to a callback as JSON lines (Gson), `SetFailedBuild` and the coverage /
  "no tests to run" suffixes of the summary line dropped.

## gopls and staticcheck analyzer docs

- Source: https://github.com/golang/tools, module `golang.org/x/tools/gopls` v0.24.0, `gopls/internal/doc/api.json`
  (key `Analyzers`: name, documentation, URL, default state)
- Licence: BSD 3-Clause — full text in [`licenses/BSD-3-Clause-Go.txt`](licenses/BSD-3-Clause-Go.txt)
- Copyright: Copyright 2009 The Go Authors
- The `SA`/`S`/`ST`/`QF` entries of that file carry check documentation from staticcheck
  (https://github.com/dominikh/go-tools, module `honnef.co/go/tools` v0.8.1, the version gopls v0.24.0 requires).
  Licence: MIT — full text in [`licenses/MIT-staticcheck.txt`](licenses/MIT-staticcheck.txt); Copyright (c) 2016
  Dominik Honnef.
- Data only, no code copied: the analyzer list and documentation texts are bundled as
  `src/main/resources/golsp/gopls-analyzers.json` and turned into the inspections in
  `src/main/resources/META-INF/golsp-gopls-analyzers.xml` (display names from the first line of each text) and
  their descriptions (rendered as HTML at runtime).

## IntelliJ Community

- Source: https://github.com/JetBrains/intellij-community
- Commit: 16a8d7c7270906a22cd45ed57ea396bc465c6798
- Licence: Apache License 2.0 — full text in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
- Copyright: Copyright 2000-2024 JetBrains s.r.o. and contributors
- Original files: `plugins/coverage-common/src/com/intellij/coverage/lcov/LcovSerializationUtils.java`
  (`convertToProjectData`, `normalizeFilePath`)
- Mapping to modified files in this project (under `src/main/kotlin/io/github/xefise/golsp/coverage/`):
  - `convertToProjectData` → `GoCoverageRunner.kt` (`projectData`)
  - `normalizeFilePath` → `GoCoverageEngine.kt` (`pathKey`)
- Changes: ported to Kotlin; input is per-line hits of a Go cover profile; partial lines get the PARTIAL status.
