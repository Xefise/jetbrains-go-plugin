#!/bin/sh
# Captures raw test binary output with -test.v=test2json (what dlv dap relays under Debug) for the packages behind
# ../../gotest-json fixtures. Binary flags mirror what `go test -json` passes; stdout and stderr share one file, as
# under `go test`. Absolute paths are replaced as in gotest-json/capture.sh.
# Usage: src/test/testData/test2json/raw/capture.sh
set -u
out="$(cd "$(dirname "$0")" && pwd)"
src="$out/../../../../../testdata/gotests"
cd "$src" || exit 1
bin="$(mktemp -d)"
trap 'rm -rf "$bin"' EXIT

capture() {
  name="$1"; pkg="$2"; shift 2
  go test -c -o "$bin/$name.test" "./$pkg" || exit 1
  (cd "$pkg" && "$bin/$name.test" -test.paniconexit0 -test.v=test2json "$@" >"$out/$name.out" 2>&1)
  echo "$?" >"$out/$name.exitcode"
}

capture calc calc -test.timeout=10m0s
capture bench calc -test.timeout=10m0s -test.run='^$' -test.bench='^BenchmarkAdd$' -test.benchtime=100x
capture panic panics -test.timeout=10m0s
capture timeout slow -test.timeout=1s
capture subtest-filter calc -test.timeout=10m0s -test.run='^TestSubtests$/^with_space$'
capture benchfail benchfail -test.timeout=10m0s -test.run='^$' -test.bench=. -test.benchtime=10x

goroot="$(go env GOROOT)"
if [ -z "$goroot" ]; then echo "go env GOROOT is empty" >&2; exit 1; fi
WORK_L="$(pwd)" WORK_P="$(pwd -P)" ROOT_L="$goroot" ROOT_P="$(cd "$goroot" && pwd -P)"
export WORK_L WORK_P ROOT_L ROOT_P
for f in "$out"/*.out; do
  awk '
    function rep(s, from, to,   out, i) {
      if (from == "") return s
      out = ""
      while ((i = index(s, from)) > 0) { out = out substr(s, 1, i - 1) to; s = substr(s, i + length(from)) }
      return out s
    }
    {
      line = rep($0, ENVIRON["WORK_L"], "/work/gotests"); line = rep(line, ENVIRON["WORK_P"], "/work/gotests")
      line = rep(line, ENVIRON["ROOT_L"], "/goroot"); print rep(line, ENVIRON["ROOT_P"], "/goroot")
    }' "$f" >"$f.tmp" && mv "$f.tmp" "$f"
done

go version >"$out/go-version.txt"
