package db.txn.lock;

public class DeadlockException extends Exception {
    public DeadlockException(String message) { super(message); }
}
