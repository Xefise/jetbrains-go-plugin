#!/bin/sh
# Re-captures the `go test -json` fixtures from testdata/gotests. Fixtures are kept unedited, except that absolute
# paths are replaced afterwards: testdata/gotests -> /work/gotests, $(go env GOROOT) -> /goroot.
# Usage: src/test/testData/gotest-json/capture.sh
set -u
out="$(cd "$(dirname "$0")" && pwd)"
src="$out/../../../../testdata/gotests"
cd "$src" || exit 1

capture() {
  name="$1"; shift
  go test -json "$@" >"$out/$name.jsonl" 2>"$out/$name.stderr"
  echo "$?" >"$out/$name.exitcode"
}

capture calc ./calc
capture bench -run '^$' -bench '^BenchmarkAdd$' -benchtime 100x ./calc
capture build-failure ./broken
capture panic ./panics
capture timeout -timeout 1s ./slow
capture all -timeout 1s ./...
capture subtest-filter -run '^TestSubtests$/^with_space$' ./calc
capture benchfail -run '^$' -bench . -benchtime 10x ./benchfail
capture rerun ./rerun

goroot="$(go env GOROOT)"
if [ -z "$goroot" ]; then echo "go env GOROOT is empty" >&2; exit 1; fi
# go prints both logical and resolved paths; awk index/substr replaces literally, ENVIRON avoids -v escape processing.
WORK_L="$(pwd)" WORK_P="$(pwd -P)" ROOT_L="$goroot" ROOT_P="$(cd "$goroot" && pwd -P)"
export WORK_L WORK_P ROOT_L ROOT_P
for f in "$out"/*.jsonl "$out"/*.stderr; do
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
