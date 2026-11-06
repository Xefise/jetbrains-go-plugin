package greet

// Hello is defined in a different module of the workspace.
func Hello(name string) string {
	return "hello, " + name
}
