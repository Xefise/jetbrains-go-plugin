func main() {
	name/*<# string #>*/ := "gopher"
	var size/*<# int #>*/ = len(name)
	data/*<# []byte #>*/, err/*<# error #>*/ := os.ReadFile(name)
}
