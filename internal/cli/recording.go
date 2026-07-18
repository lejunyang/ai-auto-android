package cli

import (
	"context"

	"github.com/lejunyang/ai-auto-android/internal/service"
)

func (a *App) executeRecording(
	ctx context.Context,
	automation service.Automation,
	args []string,
) (any, error) {
	if len(args) == 0 {
		return nil, usageError(
			"Usage: aactl recording list|replay [options]",
		)
	}
	switch args[0] {
	case "list":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device"),
			required: []string{"device"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ListRecordings(ctx, options["device"])
	case "replay":
		options, err := parseNamedOptions(args[1:], optionSpec{
			allowed:  optionSet("device", "script"),
			required: []string{"device", "script"},
		})
		if err != nil {
			return nil, err
		}
		return automation.ReplayRecording(ctx, service.ReplayRecordingRequest{
			Device:   options["device"],
			ScriptID: options["script"],
		})
	default:
		return nil, usageError(
			"Unknown recording command. Supported commands: list, replay.",
		)
	}
}
