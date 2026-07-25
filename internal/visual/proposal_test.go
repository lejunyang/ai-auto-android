// Package visual 测试只读视觉候选服务的映射和失败关闭语义。
// 测试用途：验证四种旋转下的 crop 映射、候选歧义门和 provider 图片清零。
package visual

import (
	"context"
	"errors"
	"math"
	"reflect"
	"testing"
)

func TestProposalServiceMapsCropCoordinatesThroughEveryRotation(t *testing.T) {
	tests := []struct {
		name       string
		rotation   int
		crop       PixelBounds
		wantPoint  NormalizedPoint
		wantBounds NormalizedBounds
	}{
		{
			name:       "rotation 0",
			rotation:   0,
			crop:       PixelBounds{Left: 100, Top: 400, Right: 900, Bottom: 1200},
			wantPoint:  NormalizedPoint{X: 0.5, Y: 0.4},
			wantBounds: NormalizedBounds{Left: 0.26, Top: 0.3, Right: 0.58, Bottom: 0.5},
		},
		{
			name:       "rotation 90",
			rotation:   90,
			crop:       PixelBounds{Left: 200, Top: 100, Right: 1200, Bottom: 900},
			wantPoint:  NormalizedPoint{X: 0.5, Y: 0.65},
			wantBounds: NormalizedBounds{Left: 0.3, Top: 0.6, Right: 0.7, Bottom: 0.8},
		},
		{
			name:       "rotation 180",
			rotation:   180,
			crop:       PixelBounds{Left: 100, Top: 400, Right: 900, Bottom: 1200},
			wantPoint:  NormalizedPoint{X: 0.5, Y: 0.6},
			wantBounds: NormalizedBounds{Left: 0.42, Top: 0.5, Right: 0.74, Bottom: 0.7},
		},
		{
			name:       "rotation 270",
			rotation:   270,
			crop:       PixelBounds{Left: 200, Top: 100, Right: 1200, Bottom: 900},
			wantPoint:  NormalizedPoint{X: 0.5, Y: 0.35},
			wantBounds: NormalizedBounds{Left: 0.3, Top: 0.2, Right: 0.7, Bottom: 0.4},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := trustedRegistry()
			t.Cleanup(registry.Close)
			capture := testCapture(testPNG(0))
			capture.Screen.Rotation = test.rotation
			capture.Crop = test.crop
			if _, err := registry.Register(capture); err != nil {
				t.Fatalf("Register() error = %v", err)
			}
			service := NewProposalService(
				registry,
				ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
					return []RawCandidate{rawCandidate(candidateOne, SourceModel, 0.99)}, nil
				}),
				ProposalOptions{},
			)

			response, err := service.Propose(context.Background(), validRequest())

			if err != nil {
				t.Fatalf("Propose() error = %v", err)
			}
			candidate := response.Candidates[0]
			assertPointClose(t, candidate.Point, test.wantPoint)
			assertBoundsClose(t, candidate.Bounds, test.wantBounds)
		})
	}
}

func TestProposalServiceNormalizesEverySourceThroughCropAndRotation(t *testing.T) {
	registry := registeredRegistry(t)
	provider := ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
		return []RawCandidate{
			rawCandidate("123e4567-e89b-42d3-a456-426614174101", SourceOCR, 0.99),
			rawCandidate("123e4567-e89b-42d3-a456-426614174102", SourceTemplate, 0.90),
			rawCandidate("123e4567-e89b-42d3-a456-426614174103", SourceModel, 0.80),
			rawCandidate("123e4567-e89b-42d3-a456-426614174104", SourceManual, 1.00),
		}, nil
	})
	service := NewProposalService(registry, provider, ProposalOptions{})

	response, err := service.Propose(context.Background(), validRequest())

	if err != nil {
		t.Fatalf("Propose() error = %v", err)
	}
	if len(response.Candidates) != 4 || response.ActionCommitCount != 0 {
		t.Fatalf("response = %#v", response)
	}
	wantSources := []CandidateSource{SourceOCR, SourceTemplate, SourceModel, SourceManual}
	for index, candidate := range response.Candidates {
		if candidate.Source != wantSources[index] ||
			candidate.ObservationID != observationID ||
			candidate.Point != (NormalizedPoint{X: 0.5, Y: 0.65}) ||
			candidate.Bounds != testBounds() {
			t.Fatalf("candidate %d = %#v", index, candidate)
		}
	}
	serviceType := reflect.TypeOf(service)
	for index := 0; index < serviceType.NumMethod(); index++ {
		if serviceType.Method(index).Name == "Execute" {
			t.Fatal("proposal service must not expose Execute")
		}
	}
}

func TestProposalServiceRejectsExpiredAndChangedPackageBeforeProvider(t *testing.T) {
	tests := []struct {
		name     string
		request  ProposalRequest
		wantCode Code
	}{
		{
			name: "expired",
			request: ProposalRequest{
				ObservationID:   observationID,
				ExpectedPackage: targetPackage,
				Now:             mustTime("2026-07-26T01:00:11Z"),
			},
			wantCode: CodeObservationExpired,
		},
		{
			name: "foreground package changed",
			request: ProposalRequest{
				ObservationID:   observationID,
				ExpectedPackage: "com.example.changed",
				Now:             mustTime("2026-07-26T01:00:05Z"),
			},
			wantCode: CodeForegroundPackageChanged,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := registeredRegistry(t)
			providerCalls := 0
			service := NewProposalService(
				registry,
				ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
					providerCalls++
					return nil, nil
				}),
				ProposalOptions{},
			)

			response, err := service.Propose(context.Background(), test.request)

			assertFailedResponse(t, response, err, test.wantCode)
			if providerCalls != 0 {
				t.Fatalf("provider calls = %d, want 0", providerCalls)
			}
			if _, ok := registry.Lookup(observationID); ok {
				t.Fatal("rejected observation was not revoked")
			}
		})
	}
}

func TestProposalServiceRejectsLowConfidenceAndNearbyTopCandidates(t *testing.T) {
	tests := []struct {
		name       string
		candidates []RawCandidate
		wantCode   Code
	}{
		{
			name:       "low confidence",
			candidates: []RawCandidate{rawCandidate(candidateOne, SourceModel, 0.69)},
			wantCode:   CodeCandidateLowConfidence,
		},
		{
			name: "nearby candidates with close confidence",
			candidates: []RawCandidate{
				rawCandidate(candidateOne, SourceModel, 0.91),
				func() RawCandidate {
					candidate := rawCandidate(candidateTwo, SourceOCR, 0.90)
					candidate.Point = NormalizedPoint{X: 0.51, Y: 0.51}
					return candidate
				}(),
			},
			wantCode: CodeCandidateAmbiguous,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := registeredRegistry(t)
			service := NewProposalService(
				registry,
				ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
					return test.candidates, nil
				}),
				ProposalOptions{
					MinimumConfidence: 0.7,
					AmbiguityDelta:    0.02,
					NearbyDistance:    0.05,
				},
			)

			response, err := service.Propose(context.Background(), validRequest())

			assertFailedResponse(t, response, err, test.wantCode)
			if _, ok := registry.Lookup(observationID); ok {
				t.Fatal("rejected observation was not revoked")
			}
		})
	}
}

func TestProposalServiceClearsProviderBytesAndFailsClosed(t *testing.T) {
	providerFailure := errors.New("provider detail must not escape")
	tests := []struct {
		name     string
		provider Provider
		wantCode Code
	}{
		{
			name: "no candidate",
			provider: ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
				return nil, nil
			}),
			wantCode: CodeCandidateNotFound,
		},
		{
			name: "invalid candidate",
			provider: ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
				candidate := rawCandidate(candidateOne, SourceModel, 0.99)
				candidate.Point.X = 1.1
				return []RawCandidate{candidate}, nil
			}),
			wantCode: CodeCandidateInvalid,
		},
		{
			name: "provider failure",
			provider: ProviderFunc(func(context.Context, Observation, []byte) ([]RawCandidate, error) {
				return nil, providerFailure
			}),
			wantCode: CodeProviderFailed,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			registry := registeredRegistry(t)
			var providerImage []byte
			wrapped := ProviderFunc(func(
				ctx context.Context,
				observation Observation,
				image []byte,
			) ([]RawCandidate, error) {
				providerImage = image
				return test.provider.Propose(ctx, observation, image)
			})
			service := NewProposalService(registry, wrapped, ProposalOptions{})

			response, err := service.Propose(context.Background(), validRequest())

			assertFailedResponse(t, response, err, test.wantCode)
			if !allZero(providerImage) {
				t.Fatal("provider image buffer was not cleared")
			}
			if _, ok := registry.Lookup(observationID); ok {
				t.Fatal("rejected observation was not revoked")
			}
		})
	}
}

func registeredRegistry(t *testing.T) *Registry {
	t.Helper()
	registry := trustedRegistry()
	t.Cleanup(registry.Close)
	if _, err := registry.Register(testCapture(testPNG(0))); err != nil {
		t.Fatalf("Register() error = %v", err)
	}
	return registry
}

func rawCandidate(id string, source CandidateSource, confidence float64) RawCandidate {
	return RawCandidate{
		ID:         id,
		Source:     source,
		Point:      NormalizedPoint{X: 0.5, Y: 0.5},
		Bounds:     NormalizedBounds{Left: 0.2, Top: 0.25, Right: 0.6, Bottom: 0.75},
		Confidence: confidence,
	}
}

func validRequest() ProposalRequest {
	return ProposalRequest{
		ObservationID:   observationID,
		ExpectedPackage: targetPackage,
		Now:             mustTime("2026-07-26T01:00:05Z"),
	}
}

func assertFailedResponse(t *testing.T, response ProposalResponse, err error, want Code) {
	t.Helper()
	if ErrorCode(err) != want {
		t.Fatalf("error code = %q, want %q (error=%v)", ErrorCode(err), want, err)
	}
	if len(response.Candidates) != 0 || response.ActionCommitCount != 0 {
		t.Fatalf("failed response = %#v", response)
	}
}

func assertPointClose(t *testing.T, got, want NormalizedPoint) {
	t.Helper()
	if math.Abs(got.X-want.X) > 1e-9 || math.Abs(got.Y-want.Y) > 1e-9 {
		t.Fatalf("point = %#v, want %#v", got, want)
	}
}

func assertBoundsClose(t *testing.T, got, want NormalizedBounds) {
	t.Helper()
	if math.Abs(got.Left-want.Left) > 1e-9 ||
		math.Abs(got.Top-want.Top) > 1e-9 ||
		math.Abs(got.Right-want.Right) > 1e-9 ||
		math.Abs(got.Bottom-want.Bottom) > 1e-9 {
		t.Fatalf("bounds = %#v, want %#v", got, want)
	}
}

const (
	candidateOne = "123e4567-e89b-42d3-a456-426614174101"
	candidateTwo = "123e4567-e89b-42d3-a456-426614174102"
)
