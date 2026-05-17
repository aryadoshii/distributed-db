package db.txn.lock;

public class LockTimeoutException extends Exception {
    public LockTimeoutException(String message) { super(message); }
}
