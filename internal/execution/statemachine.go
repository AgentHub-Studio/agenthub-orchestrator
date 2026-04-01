package execution

import "fmt"

// validTransitions maps each status to the set of statuses it can transition to.
var validTransitions = map[ExecutionStatus][]ExecutionStatus{
	StatusPending:   {StatusRunning, StatusCancelled},
	StatusRunning:   {StatusCompleted, StatusFailed, StatusCancelled},
	StatusCompleted: {},
	StatusFailed:    {},
	StatusCancelled: {},
}

// Transition validates and applies a status transition.
// Returns an error if the transition is not allowed.
func Transition(from, to ExecutionStatus) error {
	allowed, ok := validTransitions[from]
	if !ok {
		return fmt.Errorf("statemachine: unknown status %q", from)
	}
	for _, s := range allowed {
		if s == to {
			return nil
		}
	}
	return fmt.Errorf("statemachine: cannot transition from %q to %q", from, to)
}

// IsTerminal returns true if the status is a final state.
func IsTerminal(s ExecutionStatus) bool {
	allowed := validTransitions[s]
	return len(allowed) == 0
}
