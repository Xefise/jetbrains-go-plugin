package panics

import "testing"

func TestBeforePanic(t *testing.T) {}

func TestPanic(t *testing.T) {
	t.Run("inner", func(t *testing.T) {
		var m map[string]int
		m["boom"] = 1
	})
}

func TestNeverRuns(t *testing.T) {}
