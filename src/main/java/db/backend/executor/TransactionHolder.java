package db.sql.executor;

import db.txn.Transaction;

/**
 * A mutable reference to the current transaction.
 *
 * Problem: inner operators (SeqScanOp, InsertOp) need a Transaction at
 * execution time, but the transaction doesn't exist until TxnOperator.open()
 * calls txnManager.begin().
 *
 * Solution: pass a TransactionHolder at plan time. TxnOperator fills it in
 * open(); inner operators read it lazily on their first next() call.
 */
public class TransactionHolder {

    private Transaction txn;

    public void set(Transaction txn) { this.txn = txn; }

    public Transaction get() {
        if (txn == null) throw new IllegalStateException(
            "Transaction not yet initialized — open() must be called before next()");
        return txn;
    }
}
