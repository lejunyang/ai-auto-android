package cli

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

type documentedCommand struct {
	file string
	line int
	text string
}

func TestSkillAactlCommandExamplesMatchCurrentCLI(t *testing.T) {
	commands := documentedAactlCommands(t)
	if len(commands) < 20 {
		t.Fatalf("documented commands = %d, want at least 20", len(commands))
	}

	for _, command := range commands {
		command := command
		name := fmt.Sprintf("%s-line-%d", filepath.Base(command.file), command.line)
		t.Run(name, func(t *testing.T) {
			words, err := splitDocumentedCommand(command.text)
			if err != nil {
				t.Fatalf("%s:%d: %v", command.file, command.line, err)
			}
			if len(words) < 2 || words[0] != "aactl" {
				t.Fatalf("%s:%d: not an aactl command: %q", command.file, command.line, command.text)
			}
			args, _ := extractJSONFlag(words[1:])
			if strings.Join(args, " ") == "mcp serve" {
				return
			}

			for index := range args {
				if args[index] == "--output" && index+1 < len(args) {
					args[index+1] = filepath.Join(t.TempDir(), filepath.Base(args[index+1]))
				}
			}

			app := skillSmokeApp()
			_, runErr := app.execute(context.Background(), args)
			var appError *apperr.Error
			if errors.As(runErr, &appError) && appError.Code == apperr.CodeInvalidArgument {
				t.Fatalf(
					"%s:%d: command no longer matches the CLI: %s: %v",
					command.file,
					command.line,
					command.text,
					runErr,
				)
			}
		})
	}
}

func documentedAactlCommands(t *testing.T) []documentedCommand {
	t.Helper()
	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller() did not return the current file")
	}
	root := filepath.Clean(filepath.Join(filepath.Dir(currentFile), "..", ".."))
	skillsRoot := filepath.Join(root, "skills")
	commands := make([]documentedCommand, 0, 24)
	err := filepath.WalkDir(skillsRoot, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() || filepath.Ext(path) != ".md" {
			return nil
		}
		content, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		inBashBlock := false
		for index, line := range strings.Split(string(content), "\n") {
			trimmed := strings.TrimSpace(line)
			switch {
			case trimmed == "```bash":
				inBashBlock = true
			case strings.HasPrefix(trimmed, "```"):
				inBashBlock = false
			case inBashBlock && strings.HasPrefix(trimmed, "aactl "):
				commands = append(commands, documentedCommand{
					file: path,
					line: index + 1,
					text: trimmed,
				})
			}
		}
		return nil
	})
	if err != nil {
		t.Fatalf("scan skill commands: %v", err)
	}
	return commands
}

func splitDocumentedCommand(command string) ([]string, error) {
	words := make([]string, 0, 8)
	var current strings.Builder
	var quote rune
	escaped := false
	flush := func() {
		if current.Len() > 0 {
			words = append(words, current.String())
			current.Reset()
		}
	}

	for _, character := range command {
		if escaped {
			current.WriteRune(character)
			escaped = false
			continue
		}
		if quote == '"' && character == '\\' {
			escaped = true
			continue
		}
		if quote != 0 {
			if character == quote {
				quote = 0
			} else {
				current.WriteRune(character)
			}
			continue
		}
		switch character {
		case '\'', '"':
			quote = character
		case ' ', '\t':
			flush()
		default:
			current.WriteRune(character)
		}
	}
	if escaped || quote != 0 {
		return nil, errors.New("unterminated escape or quote")
	}
	flush()
	return words, nil
}

func skillSmokeApp() *App {
	return &App{
		Locator: fixedLocator{path: "/fake/adb"},
		Executor: &commandExecutor{
			result: process.Result{
				Stdout: []byte(
					"Android Debug Bridge version 1.0.41\nVersion 35.0.2-12147458\n",
				),
			},
		},
		Stdin:        strings.NewReader("123456\n"),
		Stdout:       io.Discard,
		Stderr:       io.Discard,
		BridgeClient: &cliBridgeClient{},
		BridgeStore: &cliSessionStore{
			session: cliValidSession(),
			exists:  true,
		},
		Automation: &actionCapturingAutomation{},
	}
}
