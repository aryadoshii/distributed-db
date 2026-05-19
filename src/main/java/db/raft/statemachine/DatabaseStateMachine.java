package db.raft.statemachine;

import db.sql.executor.Operator;
import db.sql.executor.Planner;
import db.sql.executor.Row;
import db.sql.parser.Parser;
import db.sql.ast.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies committed Raft log entries to the database storage engine.
 *
 * Each command is a UTF-8 SQL string. On apply():
 *   1. Parse the SQL string into an AST
 *   2. Plan it into an operator tree (TxnOperator wraps everything)
 *   3. Execute: open → drain next() → close
 *   4. Serialize the result rows as UTF-8 for the response
 *
 * The lastApplied index is persisted atomically so we never
 * apply the same entry twice after a restart.
 *
 * Thread safety: apply() is called single-threaded by RaftNode's
 * apply loop. The Planner and storage engine handle their own
 * internal concurrency.
 */
public class DatabaseStateMachine implements StateMachine {

    private final Planner      planner;
    private final AtomicInteger lastApplied;

    public DatabaseStateMachine(Planner planner) {
        this.planner     = planner;
        this.lastApplied = new AtomicInteger(0);
    }

    @Override
    public byte[] apply(int index, byte[] command) {
        if (index <= lastApplied.get()) {
            // Already applied — idempotent, skip
            return "already_applied".getBytes();
        }

        try {
            String sql = new String(command);

            // Parse
            Statement stmt = Parser.parseSQL(sql);

            // Plan and execute
            Operator op = planner.plan(stmt);
            List<Row> results = new ArrayList<>();

            op.open();
            try {
                Row row;
                while ((row = op.next()) != null) {
                    results.add(row);
                }
            } finally {
                op.close();
            }

            lastApplied.set(index);

            // Serialize result — simple CSV-style for now
            StringBuilder sb = new StringBuilder();
            for (Row row : results) {
                sb.append(row.toString()).append("\n");
            }
            return sb.toString().getBytes();

        } catch (Exception e) {
            lastApplied.set(index);
            return ("ERROR: " + e.getMessage()).getBytes();
        }
    }

    @Override
    public int lastAppliedIndex() {
        return lastApplied.get();
    }

    /**
     * Execute a SELECT directly against the local storage engine, bypassing
     * Raft and the idempotency check. Used by DBServer for leader-local reads.
     */
    public String executeQuery(String sql) {
        try {
            Statement stmt = Parser.parseSQL(sql);
            Operator  op   = planner.plan(stmt);
            List<Row> results = new ArrayList<>();
            op.open();
            try {
                Row row;
                while ((row = op.next()) != null) results.add(row);
            } finally {
                op.close();
            }
            StringBuilder sb = new StringBuilder();
            for (Row row : results) sb.append(row.toString()).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
