package rerun

import "testing"

func TestTable(t *testing.T) {
	for _, tc := range []struct {
		name string
		ok   bool
	}{
		{"same", true},
		{"same", false},
		{"same", true},
		{"with space", false},
		{"with space", true},
		{"a/b", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if !tc.ok {
				t.Error("case failed")
			}
		})
	}
}

func TestParentFails(t *testing.T) {
	t.Run("child", func(t *testing.T) {})
	t.Error("parent failed")
}

func TestPasses(t *testing.T) {}

func TestPassesToo(t *testing.T) {}
