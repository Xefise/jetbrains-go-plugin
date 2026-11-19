# Go (gopls) for WebStorm

Go support for WebStorm and other JetBrains IDEs that don't have the official Go plugin.

Language features come from [gopls](https://go.dev/gopls/) over LSP (via [LSP4IJ](https://github.com/redhat-developer/lsp4ij)),
debugging from [Delve](https://github.com/go-delve/delve) over DAP. The plugin adds run configurations, gutter icons,
a test runner, coverage and toolchain setup on top.

> **Status: alpha (0.1.x).** Used daily by the author, but expect rough edges. Bug reports are welcome in
> [Issues](https://github.com/Xefise/jetbrains-go-plugin/issues).
>
> Maintained in spare time on a best-effort basis: issues get read, but fixes and new features may take a while.
> Pull requests are welcome.

## Features

**Editor**
- Highlighting (lexer + gopls semantic tokens), completion, go to declaration, find usages, rename, quick documentation
- Diagnostics from gopls; gopls and staticcheck analyzers exposed as regular inspections
- Formatting and organize imports on save, inlay hints
- Smart Enter, live templates, Surround With
- gopls refactorings (extract function/variable/constant, inline, …) in Refactor This and Alt+Enter
- `go.mod`, `go.work` and `go.sum` files

**Run and debug**
- ▶ in the gutter for `func main`, tests, subtests (`t.Run("name", …)`), benchmarks, fuzz targets and examples
- Go Application configuration: file or package, args, env and env file, working directory, build flags, race detector
- Go Test configuration: package, file or single test; test tree with subtests, rerun failed tests
- Debugging with `dlv dap`: breakpoints, variables, goroutines
- Attach to a local Go process; remote debugging against `dlv --headless` with path mapping
- Run tests with coverage
- Clickable `file.go:line:col` links in the console

**Toolchain**
- Finds Go automatically (PATH, `~/go/bin`, GOROOT, standard locations)
- Offers to install or update gopls and dlv with `go install`
- Settings under **Settings → Languages & Frameworks → Go**

## Requirements

- WebStorm 2026.3 or newer (build 263+); other JetBrains IDEs without the Go plugin should work too
- [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) 0.21.0 or newer
- Go installed on the machine
- The project must be trusted: in an untrusted project neither `go` nor gopls is started

Not compatible with the official JetBrains Go plugin (GoLand, IntelliJ IDEA Ultimate) or other third-party Go plugins.

## Installation

1. Install **LSP4IJ** from the Marketplace: **Settings → Plugins → Marketplace**.
2. Download `golsp-*.zip` from [Releases](https://github.com/Xefise/jetbrains-go-plugin/releases).
3. **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart the IDE.
4. Open a Go project. If gopls or dlv is missing, the editor banner offers to install it.

## Building from source

Requires JDK 25 and a local WebStorm 2026.3 installation.

```shell
echo "webstormPath=/Applications/WebStorm.app" > local.properties
./gradlew buildPlugin      # zip in build/distributions/
./gradlew runIde           # sandbox IDE with testdata/hello
./gradlew test             # unit and platform tests
./gradlew integrationTest  # tests against real go, gopls and dlv
```

## License

[Apache License 2.0](LICENSE). Third-party code and its licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
