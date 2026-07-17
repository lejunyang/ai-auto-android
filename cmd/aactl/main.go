package main

import (
	"context"
	"os"
	"os/signal"

	"github.com/lejunyang/ai-auto-android/internal/cli"
)

func main() {
	contextWithSignal, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	app := cli.DefaultApp(os.Stdin, os.Stdout, os.Stderr)
	os.Exit(app.Run(contextWithSignal, os.Args[1:]))
}
