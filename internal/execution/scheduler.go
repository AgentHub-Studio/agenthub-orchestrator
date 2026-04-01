package execution

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
)

// Scheduler executes a DAG using parallel goroutines per level.
type Scheduler struct{}

// NewScheduler creates a Scheduler.
func NewScheduler() *Scheduler { return &Scheduler{} }

// Run executes the DAG in topological order, parallelising nodes at each level.
// Each node's output is stored in pctx so downstream nodes can access it.
func (s *Scheduler) Run(ctx context.Context, dag *DAG, pctx *PipelineContext) error {
	levels, err := dag.Levels()
	if err != nil {
		return fmt.Errorf("scheduler: build levels: %w", err)
	}

	for levelIdx, nodes := range levels {
		slog.Debug("executing level", "level", levelIdx, "nodeCount", len(nodes))

		var wg sync.WaitGroup
		errs := make(chan error, len(nodes))

		for _, node := range nodes {
			wg.Add(1)
			go func(n *Node) {
				defer wg.Done()

				exec, err := GetNodeExecutor(n.Type)
				if err != nil {
					errs <- fmt.Errorf("scheduler: node %q: %w", n.ID, err)
					return
				}

				output, err := exec.Execute(ctx, n, pctx)
				if err != nil {
					errs <- fmt.Errorf("scheduler: node %q execution failed: %w", n.ID, err)
					return
				}

				pctx.SetNodeOutput(n.ID, output)
				slog.Debug("node completed", "nodeId", n.ID, "type", n.Type)
			}(node)
		}

		wg.Wait()
		close(errs)

		// Collect any errors from this level.
		for err := range errs {
			if err != nil {
				return err
			}
		}

		// Check if context was cancelled between levels.
		if ctx.Err() != nil {
			return ctx.Err()
		}
	}

	return nil
}
