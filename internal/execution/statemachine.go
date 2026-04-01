package execution

import (
	"errors"
	"fmt"
)

// ErrInvalidTransition is returned when a status transition is not allowed.
var ErrInvalidTransition = errors.New("invalid status transition")

// StatusSkipped is only valid for node executions (not agent executions).
const StatusSkipped ExecutionStatus = "SKIPPED"

// executionTransitions maps valid agent-level status transitions.
var executionTransitions = map[ExecutionStatus][]ExecutionStatus{
	StatusPending:   {StatusRunning, StatusCancelled},
	StatusRunning:   {StatusCompleted, StatusFailed, StatusCancelled},
	StatusCompleted: {},
	StatusFailed:    {},
	StatusCancelled: {},
}

// nodeTransitions maps valid node-level status transitions (adds SKIPPED).
var nodeTransitions = map[ExecutionStatus][]ExecutionStatus{
	StatusPending:   {StatusRunning, StatusCancelled, StatusSkipped},
	StatusRunning:   {StatusCompleted, StatusFailed, StatusCancelled},
	StatusCompleted: {},
	StatusFailed:    {},
	StatusCancelled: {},
	StatusSkipped:   {},
}

// CanTransition returns true if transitioning from → to is allowed for agent executions.
func CanTransition(from, to ExecutionStatus) bool {
	return canTransitionIn(executionTransitions, from, to)
}

// CanTransitionNode returns true if transitioning from → to is allowed for node executions.
func CanTransitionNode(from, to ExecutionStatus) bool {
	return canTransitionIn(nodeTransitions, from, to)
}

func canTransitionIn(table map[ExecutionStatus][]ExecutionStatus, from, to ExecutionStatus) bool {
	allowed, ok := table[from]
	if !ok {
		return false
	}
	for _, s := range allowed {
		if s == to {
			return true
		}
	}
	return false
}

// Transition validates a status transition for agent executions.
// Returns ErrInvalidTransition if the transition is not allowed.
func Transition(from, to ExecutionStatus) error {
	if _, ok := executionTransitions[from]; !ok {
		return fmt.Errorf("%w: unknown status %q", ErrInvalidTransition, from)
	}
	if !CanTransition(from, to) {
		return fmt.Errorf("%w: %q → %q", ErrInvalidTransition, from, to)
	}
	return nil
}

// TransitionNode validates a status transition for node executions (allows SKIPPED).
// Returns ErrInvalidTransition if the transition is not allowed.
func TransitionNode(from, to ExecutionStatus) error {
	if _, ok := nodeTransitions[from]; !ok {
		return fmt.Errorf("%w: unknown status %q", ErrInvalidTransition, from)
	}
	if !CanTransitionNode(from, to) {
		return fmt.Errorf("%w: %q → %q", ErrInvalidTransition, from, to)
	}
	return nil
}

// IsTerminal returns true if the status is a final state for agent executions.
func IsTerminal(s ExecutionStatus) bool {
	allowed := executionTransitions[s]
	return len(allowed) == 0
}

// IsTerminalNode returns true if the status is a final state for node executions.
func IsTerminalNode(s ExecutionStatus) bool {
	allowed := nodeTransitions[s]
	return len(allowed) == 0
}
