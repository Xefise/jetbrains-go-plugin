func Map[T, U any](items []T, f func(T) U) []U

func labels(ids []int) []string {
	return Map/*<# [int, string] #>*/(ids, strconv.Itoa)
}
