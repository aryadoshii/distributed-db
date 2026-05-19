package db.raft.statemachine;

/**
 * Interface for the Raft state machine.
 *
 * Once a log entry is committed (majority of nodes have it),
 * Raft calls apply() to execute the command against the state machine.
 *
 * In our database, the state machine IS the database storage engine —
 * applying a command means executing the SQL against the B-tree,
 * transaction manager, and executor.
 *
 * Guarantees:
 *   - apply() is called in log order (index 1, then 2, then 3, ...)
 *   - apply() is called at most once per log index
 *   - apply() is never called concurrently (single-threaded apply loop)
 */
public interface StateMachine {

    /**
     * Apply a committed log entry to the state machine.
     *
     * @param index   the log index of this entry (for idempotency tracking)
     * @param command the raw SQL bytes to execute
     * @return result bytes (e.g. serialized row count or error message)
     */
    byte[] apply(int index, byte[] command);

    /**
     * Return the index of the last entry applied to this state machine.
     * Used after a restart to know where to resume applying from the log.
     */
    int lastAppliedIndex();
}
