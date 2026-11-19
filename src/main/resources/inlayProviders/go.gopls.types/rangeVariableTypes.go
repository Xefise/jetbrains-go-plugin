func count(ages map[string]int) {
	for name/*<# string #>*/, age/*<# int #>*/ := range ages {
		fmt.Println(name, age)
	}
}
