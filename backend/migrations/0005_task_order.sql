-- Manual order of tasks: position among the tasks with the same list and the same parent task
-- (top-level tasks of a list, or the subtasks of one task). Smaller first; ties by creation time.
ALTER TABLE tasks ADD COLUMN position INTEGER NOT NULL DEFAULT 0;

-- Start with the order shown so far: top-level tasks by due date (without date last), subtasks in creation order
UPDATE tasks SET position = (
    SELECT rn FROM (
        SELECT id, ROW_NUMBER() OVER (
            PARTITION BY list_id, parent_id
            ORDER BY CASE WHEN parent_id IS NULL THEN due_at IS NULL END,
                     CASE WHEN parent_id IS NULL THEN due_at END,
                     created_at, id
        ) AS rn FROM tasks
    ) o WHERE o.id = tasks.id
);
CREATE INDEX idx_tasks_order ON tasks(list_id, parent_id, position);

-- How a list's open tasks are sorted: 'manual' (own order, by drag & drop) or 'due' (by due date)
ALTER TABLE task_lists ADD COLUMN sort_by TEXT NOT NULL DEFAULT 'manual';
