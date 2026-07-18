package adb

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/lejunyang/ai-auto-android/internal/apperr"
	"github.com/lejunyang/ai-auto-android/internal/process"
)

const MaxTCPPort = 65535

type Forward struct {
	Device     string `json:"device"`
	LocalPort  int    `json:"localPort"`
	RemotePort int    `json:"remotePort"`
}

func (c *Client) Forward(ctx context.Context, serial string, remotePort int) (Forward, error) {
	if err := validateForward(ctx, c, serial, remotePort); err != nil {
		return Forward{}, err
	}
	result, err := c.run(
		ctx,
		[]string{
			"-s", serial,
			"forward",
			"tcp:0",
			fmt.Sprintf("tcp:%d", remotePort),
		},
		process.Options{},
	)
	if err != nil {
		return Forward{}, err
	}
	if result.OutputTruncated {
		return Forward{}, apperr.New(
			apperr.CodeProtocol,
			"ADB returned a truncated forward allocation.",
			false,
			nil,
		)
	}
	localPort, parseErr := strconv.Atoi(strings.TrimSpace(string(result.Stdout)))
	if parseErr != nil || localPort < 1 || localPort > MaxTCPPort {
		return Forward{}, apperr.New(
			apperr.CodeProtocol,
			"ADB did not return a valid allocated local port.",
			false,
			nil,
		)
	}
	return Forward{
		Device:     serial,
		LocalPort:  localPort,
		RemotePort: remotePort,
	}, nil
}

func (c *Client) RemoveForward(ctx context.Context, serial string, localPort int) error {
	if err := ValidateSerial(serial); err != nil {
		return err
	}
	if localPort < 1 || localPort > MaxTCPPort {
		return invalidField("localPort", "must be between 1 and 65535")
	}
	_, err := c.run(
		ctx,
		[]string{
			"-s", serial,
			"forward",
			"--remove",
			fmt.Sprintf("tcp:%d", localPort),
		},
		process.Options{},
	)
	return err
}

func validateForward(
	ctx context.Context,
	client *Client,
	serial string,
	remotePort int,
) error {
	if err := ValidateSerial(serial); err != nil {
		return err
	}
	if remotePort < 1 || remotePort > MaxTCPPort {
		return invalidField("remotePort", "must be between 1 and 65535")
	}
	return client.requireOnlineDevice(ctx, serial)
}
