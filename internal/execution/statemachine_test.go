package execution_test

import (
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/AgentHub-Studio/agenthub-orchestrator/internal/execution"
)

// --- Agent execution transitions ---

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
		assert.True(t, errors.Is(err, execution.ErrInvalidTransition))
	}
}

func TestTransition_UnknownStatus(t *testing.T) {
	err := execution.Transition("UNKNOWN", execution.StatusRunning)
	require.Error(t, err)
	assert.True(t, errors.Is(err, execution.ErrInvalidTransition))
	assert.Contains(t, err.Error(), "unknown status")
}

func TestCanTransition_AgentLevel(t *testing.T) {
	assert.True(t, execution.CanTransition(execution.StatusPending, execution.StatusRunning))
	assert.False(t, execution.CanTransition(execution.StatusCompleted, execution.StatusRunning))
	// SKIPPED is not allowed at agent level
	assert.False(t, execution.CanTransition(execution.StatusPending, execution.StatusSkipped))
}

// --- Node execution transitions ---

func TestTransitionNode_ValidPaths(t *testing.T) {
	cases := []struct {
		from execution.ExecutionStatus
		to   execution.ExecutionStatus
	}{
		{execution.StatusPending, execution.StatusRunning},
		{execution.StatusPending, execution.StatusSkipped},
		{execution.StatusPending, execution.StatusCancelled},
		{execution.StatusRunning, execution.StatusCompleted},
		{execution.StatusRunning, execution.StatusFailed},
		{execution.StatusRunning, execution.StatusCancelled},
	}
	for _, tc := range cases {
		err := execution.TransitionNode(tc.from, tc.to)
		require.NoError(t, err, "expected %s → %s to be valid for node", tc.from, tc.to)
	}
}

func TestTransitionNode_InvalidPaths(t *testing.T) {
	cases := []struct {
		from execution.ExecutionStatus
		to   execution.ExecutionStatus
	}{
		{execution.StatusCompleted, execution.StatusRunning},
		{execution.StatusSkipped, execution.StatusRunning},
		{execution.StatusFailed, execution.StatusCompleted},
	}
	for _, tc := range cases {
		err := execution.TransitionNode(tc.from, tc.to)
		require.Error(t, err)
		assert.True(t, errors.Is(err, execution.ErrInvalidTransition))
	}
}

func TestCanTransitionNode_Skipped(t *testing.T) {
	assert.True(t, execution.CanTransitionNode(execution.StatusPending, execution.StatusSkipped))
	// SKIPPED is terminal
	assert.False(t, execution.CanTransitionNode(execution.StatusSkipped, execution.StatusRunning))
}

// --- IsTerminal ---

func TestIsTerminal(t *testing.T) {
	assert.True(t, execution.IsTerminal(execution.StatusCompleted))
	assert.True(t, execution.IsTerminal(execution.StatusFailed))
	assert.True(t, execution.IsTerminal(execution.StatusCancelled))

	assert.False(t, execution.IsTerminal(execution.StatusPending))
	assert.False(t, execution.IsTerminal(execution.StatusRunning))
}

func TestIsTerminalNode(t *testing.T) {
	assert.True(t, execution.IsTerminalNode(execution.StatusCompleted))
	assert.True(t, execution.IsTerminalNode(execution.StatusFailed))
	assert.True(t, execution.IsTerminalNode(execution.StatusCancelled))
	assert.True(t, execution.IsTerminalNode(execution.StatusSkipped))

	assert.False(t, execution.IsTerminalNode(execution.StatusPending))
	assert.False(t, execution.IsTerminalNode(execution.StatusRunning))
}
