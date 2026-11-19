package checkme

import "fmt"

func Grade(n int) string {
	if n == 1 {
		return "one"
	} else if n == 2 {
		return "two"
	} else if n == 3 {
		return "three"
	}
	return "many"
}

func Wait(ch chan int) {
	select {
	case v := <-ch:
		fmt.Println(v)
	}
}

func Overwrite() int {
	x := 1
	x = 2
	return x
}
