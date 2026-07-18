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
	if len(args) == 0 || args[0] != "replay" {
		return nil, usageError(
			"Usage: aactl recording replay --device SERIAL --script UUID [--json]",
		)
	}
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
}
