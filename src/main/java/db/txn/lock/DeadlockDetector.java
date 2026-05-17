package db.txn.lock;

import java.util.*;

/**
 * Detects deadlock cycles in a wait-for graph and returns the victim txnId.
 * The victim is chosen as the highest txnId in any detected cycle (youngest transaction).
 */
public final class DeadlockDetector {

    /**
     * Build a wait-for graph from the current lock map and detect cycles.
     *
     * @param lockMap  map from rowKey → LockTable
     * @return txnId of the chosen victim, or -1 if no cycle detected
     */
    public static long detectAndChooseVictim(Map<Integer, LockTable> lockMap) {
        // Build wait-for graph: waiter -> holder
        Map<Long, Long> waitFor = new HashMap<>();
        for (LockTable lt : lockMap.values()) {
            long holder = lt.getHolderTxnId();
            if (holder == -1L) continue;
            for (long waiter : lt.getWaiters()) {
                waitFor.put(waiter, holder);
            }
        }

        // DFS cycle detection — find any cycle and return max txnId in it
        Set<Long> visited  = new HashSet<>();
        Set<Long> inStack  = new HashSet<>();

        for (long start : waitFor.keySet()) {
            long victim = dfs(start, waitFor, visited, inStack);
            if (victim != -1L) return victim;
        }
        return -1L;
    }

    private static long dfs(long node, Map<Long, Long> waitFor,
                             Set<Long> visited, Set<Long> inStack) {
        if (inStack.contains(node)) return node;   // cycle found; node is in it
        if (visited.contains(node)) return -1L;

        visited.add(node);
        inStack.add(node);

        Long next = waitFor.get(node);
        if (next != null) {
            long victim = dfs(next, waitFor, visited, inStack);
            if (victim != -1L) {
                // propagate highest txnId in the cycle as victim
                return Math.max(victim, node);
            }
        }

        inStack.remove(node);
        return -1L;
    }
}
