package execution

import "fmt"

// Node represents a pipeline node in the DAG.
type Node struct {
	ID     string
	Type   string
	Config map[string]any
	Deps   []string // node IDs this node depends on (upstream)
}

// Edge represents a directed connection between two nodes.
type Edge struct {
	Source string
	Target string
}

// DAG is a directed acyclic graph of pipeline nodes.
type DAG struct {
	Nodes map[string]*Node
	Edges []Edge
}

// NewDAG constructs a DAG from node and edge lists.
func NewDAG(nodes []*Node, edges []Edge) *DAG {
	nodeMap := make(map[string]*Node, len(nodes))
	for _, n := range nodes {
		nodeMap[n.ID] = n
	}

	// Build dependency list from edges.
	for _, e := range edges {
		if target, ok := nodeMap[e.Target]; ok {
			target.Deps = append(target.Deps, e.Source)
		}
	}

	return &DAG{Nodes: nodeMap, Edges: edges}
}

// TopologicalSort returns nodes in topological order (dependencies before dependents).
// Returns an error if a cycle is detected.
func (d *DAG) TopologicalSort() ([]*Node, error) {
	type state int
	const (
		unvisited state = iota
		visiting
		visited
	)

	states := make(map[string]state, len(d.Nodes))
	var sorted []*Node

	var visit func(id string) error
	visit = func(id string) error {
		switch states[id] {
		case visiting:
			return fmt.Errorf("dag: cycle detected at node %q", id)
		case visited:
			return nil
		}
		states[id] = visiting
		node := d.Nodes[id]
		if node == nil {
			return fmt.Errorf("dag: node %q not found", id)
		}
		for _, dep := range node.Deps {
			if err := visit(dep); err != nil {
				return err
			}
		}
		states[id] = visited
		sorted = append(sorted, node)
		return nil
	}

	for id := range d.Nodes {
		if err := visit(id); err != nil {
			return nil, err
		}
	}

	return sorted, nil
}

// Levels groups nodes into levels that can be executed in parallel.
// All nodes in the same level have all their dependencies satisfied by prior levels.
func (d *DAG) Levels() ([][]*Node, error) {
	sorted, err := d.TopologicalSort()
	if err != nil {
		return nil, err
	}

	// Compute level (max depth) for each node.
	level := make(map[string]int, len(d.Nodes))
	for _, n := range sorted {
		max := 0
		for _, dep := range n.Deps {
			if l := level[dep] + 1; l > max {
				max = l
			}
		}
		level[n.ID] = max
	}

	// Find max level.
	maxLevel := 0
	for _, l := range level {
		if l > maxLevel {
			maxLevel = l
		}
	}

	// Group by level.
	levels := make([][]*Node, maxLevel+1)
	for id, l := range level {
		levels[l] = append(levels[l], d.Nodes[id])
	}

	return levels, nil
}
