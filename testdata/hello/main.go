package main

import (
	"fmt"
	"sync"
)

type Point struct {
	X, Y int
	Name string
}

func square(n int) int {
	return n * n
}

func worker(id int, points []Point, results map[string]int, mu *sync.Mutex, wg *sync.WaitGroup) {
	defer wg.Done()
	sum := 0
	for _, p := range points {
		sum += square(p.X) + square(p.Y)
	}
	mu.Lock()
	results[fmt.Sprintf("worker-%d", id)] = sum
	mu.Unlock()
}

func main() {
	origin := Point{X: 0, Y: 0, Name: "origin"}
	points := []Point{origin, {X: 1, Y: 2, Name: "a"}, {X: 3, Y: 4, Name: "b"}}
	counts := map[string]int{"apples": 3, "pears": 5}

	fmt.Println("hello")
	fmt.Println()
	fmt.Println()
	fmt.Println("after empty lines")

	total := 0
	for i := 0; i < 5; i++ {
		total += square(i)
	}
	fmt.Println("total:", total)

	results := make(map[string]int)
	var mu sync.Mutex
	var wg sync.WaitGroup
	for id := 1; id <= 3; id++ {
		wg.Add(1)
		go worker(id, points, results, &mu, &wg)
	}
	wg.Wait()

	fmt.Println(origin, points, counts)
	fmt.Println(results)
}
