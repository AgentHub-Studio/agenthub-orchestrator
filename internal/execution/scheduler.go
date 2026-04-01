package execution

import (
	"context"
	"fmt"
	"log/slog"

	"golang.org/x/sync/errgroup"
)

const defaultMaxParallelNodes = 8

// Scheduler executes a DAG using parallel goroutines per level.
type Scheduler struct {
	nodeRegistry     *NodeRegistry
	maxParallelNodes int
}

// NewScheduler creates a Scheduler with the given node registry.
// maxParallelNodes limits simultaneous goroutines via a semaphore channel (0 = default 8).
func NewScheduler(nodeRegistry *NodeRegistry, maxParallelNodes ...int) *Scheduler {
	max := defaultMaxParallelNodes
	if len(maxParallelNodes) > 0 && maxParallelNodes[0] > 0 {
		max = maxParallelNodes[0]
	}
	return &Scheduler{nodeRegistry: nodeRegistry, maxParallelNodes: max}
}

// Run executes the DAG in topological order, parallelising nodes at each level.
//
// Nodes at the same topological level run concurrently via goroutines.
// A semaphore limits the total number of simultaneous goroutines to maxParallelNodes.
// If any node returns an error, errgroup cancels the level context so all other
// in-flight goroutines abort at their next ctx.Done() check.
// Each node's output is stored in pctx so downstream nodes can access it.
func (s *Scheduler) Run(ctx context.Context, dag *DAG, pctx *PipelineContext) error {
	levels, err := dag.Levels()
	if err != nil {
		return fmt.Errorf("scheduler: build levels: %w", err)
	}

	// sem is a buffered channel acting as a semaphore to bound parallelism.
	sem := make(chan struct{}, s.maxParallelNodes)

	for levelIdx, nodes := range levels {
		slog.Debug("executing level", "level", levelIdx, "nodeCount", len(nodes))

		// errgroup propagates the first error and cancels gctx for all goroutines.
		g, gctx := errgroup.WithContext(ctx)

		for _, node := range nodes {
			n := node // loop-variable capture
			g.Go(func() error {
				// Acquire semaphore — blocks when maxParallelNodes goroutines are running.
				select {
				case sem <- struct{}{}:
				case <-gctx.Done():
					return gctx.Err()
				}
				defer func() { <-sem }()

				exec, err := s.nodeRegistry.Get(n.Type)
				if err != nil {
					return fmt.Errorf("scheduler: node %q: %w", n.ID, err)
				}

				output, err := exec.Execute(gctx, n, pctx)
				if err != nil {
					return fmt.Errorf("scheduler: node %q execution failed: %w", n.ID, err)
				}

				pctx.SetNodeOutput(n.ID, output)
				slog.Debug("node completed", "nodeId", n.ID, "type", n.Type)
				return nil
			})
		}

		// Wait for the entire level. On the first error, errgroup has already
		// cancelled gctx, so remaining goroutines exit at their ctx.Done() check.
		if err := g.Wait(); err != nil {
			return err
		}

		// Propagate outer-context cancellation between levels.
		if ctx.Err() != nil {
			return ctx.Err()
		}
	}

	return nil
}
