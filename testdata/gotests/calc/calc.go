package calc

func Add(a, b int) int { return a + b }

// Sub is deliberately wrong so that TestSub fails.
func Sub(a, b int) int { return a + b }

func Reverse(s string) string {
	r := []rune(s)
	for i, j := 0, len(r)-1; i < j; i, j = i+1, j-1 {
		r[i], r[j] = r[j], r[i]
	}
	return string(r)
}
