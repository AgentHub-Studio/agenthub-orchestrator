package execution_test

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
)

func TestTransition_ValidPaths(t *testing.T) {
	cases := []struct {
		from execution.ExecutionStatus
		to   execution.ExecutionStatus
	}{
		{execution.StatusPending, execution.StatusRunning},
		{execution.StatusPending, execution.StatusCancelled},
		{execution.StatusRunning, execution.StatusCompleted},
		{execution.StatusRunning, execution.StatusFailed},
		{execution.StatusRunning, execution.StatusCancelled},
	}
	for _, tc := range cases {
		err := execution.Transition(tc.from, tc.to)
		require.NoError(t, err, "expected %s → %s to be valid", tc.from, tc.to)
	}
}

func TestTransition_InvalidPaths(t *testing.T) {
	cases := []struct {
		from execution.ExecutionStatus
		to   execution.ExecutionStatus
	}{
		{execution.StatusCompleted, execution.StatusRunning},
		{execution.StatusFailed, execution.StatusRunning},
		{execution.StatusCancelled, execution.StatusRunning},
		{execution.StatusPending, execution.StatusCompleted},
		{execution.StatusPending, execution.StatusFailed},
	}
	for _, tc := range cases {
		err := execution.Transition(tc.from, tc.to)
		require.Error(t, err, "expected %s → %s to be invalid", tc.from, tc.to)
	}
}

func TestTransition_UnknownStatus(t *testing.T) {
	err := execution.Transition("UNKNOWN", execution.StatusRunning)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "unknown status")
}

func TestIsTerminal(t *testing.T) {
	assert.True(t, execution.IsTerminal(execution.StatusCompleted))
	assert.True(t, execution.IsTerminal(execution.StatusFailed))
	assert.True(t, execution.IsTerminal(execution.StatusCancelled))

	assert.False(t, execution.IsTerminal(execution.StatusPending))
	assert.False(t, execution.IsTerminal(execution.StatusRunning))
}
