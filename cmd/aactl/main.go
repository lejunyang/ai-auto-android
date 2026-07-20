// Package main 提供 aactl 命令行程序入口，并把中断信号传递给统一 CLI 执行器。
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
