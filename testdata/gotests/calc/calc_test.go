package calc

import (
	"fmt"
	"testing"
	"time"
)

func TestAdd(t *testing.T) {
	if got := Add(2, 3); got != 5 {
		t.Fatalf("Add(2, 3) = %d, want 5", got)
	}
}

func TestSub(t *testing.T) {
	if got := Sub(5, 3); got != 2 {
		t.Errorf("Sub(5, 3) = %d, want 2", got)
	}
}

func TestSubtests(t *testing.T) {
	t.Run("plain", func(t *testing.T) {})
	t.Run("with space", func(t *testing.T) {
		t.Log("output from a subtest\nsecond line")
	})
	t.Run("a/b", func(t *testing.T) {})
	t.Run("nested", func(t *testing.T) {
		t.Run("ok", func(t *testing.T) {})
		t.Run("fails", func(t *testing.T) {
			t.Error("nested failure")
		})
	})
	t.Run("регэксп [x]+$", func(t *testing.T) {})
}

func TestParallel(t *testing.T) {
	for _, name := range []string{"first", "second", "third"} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			time.Sleep(20 * time.Millisecond)
			t.Logf("done %s", name)
		})
	}
}

func TestParallelTopA(t *testing.T) {
	t.Parallel()
	time.Sleep(30 * time.Millisecond)
	fmt.Println("stdout from A")
}

func TestParallelTopB(t *testing.T) {
	t.Parallel()
	time.Sleep(10 * time.Millisecond)
	fmt.Print("partial line from B without newline")
}

func TestSkipped(t *testing.T) {
	t.Skip("not today")
}

func TestMain(m *testing.M) {
	m.Run()
}

func BenchmarkAdd(b *testing.B) {
	for i := 0; i < b.N; i++ {
		Add(i, i)
	}
}

func ExampleAdd() {
	fmt.Println(Add(1, 2))
	// Output: 3
}

func ExampleSub() {
	fmt.Println(Sub(5, 3))
	// Output: 2
}

func FuzzReverse(f *testing.F) {
	f.Add("hello")
	f.Add("мир")
	f.Fuzz(func(t *testing.T, s string) {
		if Reverse(Reverse(s)) != s {
			t.Errorf("double reverse of %q", s)
		}
	})
}
