package mcpserver

import (
	"strings"
	"testing"

	"github.com/lejunyang/ai-auto-android/internal/service"
)

func TestModelRiskGateUsesExplicitAllowlist(t *testing.T) {
	for _, action := range []string{
		service.ActionTap,
		service.ActionSwipe,
		service.ActionSetText,
		service.ActionPressKey,
		service.ActionLaunch,
	} {
		if err := requireModelActionAllowed(action); err != nil {
			t.Fatalf("allowed action %q error = %v", action, err)
		}
	}

	for _, action := range []string{
		service.ActionStop,
		"shell",
		"app.install",
		"app.uninstall",
		"permission.grant",
		"payment.submit",
		"data.delete",
	} {
		err := requireModelActionAllowed(action)
		if err == nil || !strings.Contains(err.Error(), `"code":"ACTION_NOT_ALLOWED"`) {
			t.Fatalf("denied action %q error = %v", action, err)
		}
	}
}

func TestModelRiskGateAlwaysRequiresHumanConfirmationForReplay(t *testing.T) {
	err := requireReplayConfirmation()
	if err == nil || !strings.Contains(err.Error(), `"code":"CONFIRMATION_REQUIRED"`) {
		t.Fatalf("replay gate error = %v", err)
	}
}
