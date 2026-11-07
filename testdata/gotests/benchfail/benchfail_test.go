package benchfail

import "testing"

func BenchmarkPasses(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = i * i
	}
}

func BenchmarkFails(b *testing.B) {
	b.Fatal("benchmark failure")
}
