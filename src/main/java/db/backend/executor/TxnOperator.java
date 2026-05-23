package db.sql.executor;

import db.txn.Transaction;
import db.txn.TransactionManager;

/**
 * Wraps any operator tree with a full transaction lifecycle.
 *
 * open()  → begin txn, fill TransactionHolder so inner operators can read it, open inner
 * next()  → delegate; on any RuntimeException abort then re-throw
 * close() → commit if no abort occurred, always close inner
 */
public class TxnOperator implements Operator {

    private final Operator           inner;
    private final TransactionManager txnManager;
    private final TransactionHolder  holder;

    private Transaction currentTxn;
    private boolean     aborted;

    public TxnOperator(Operator inner,
                       TransactionManager txnManager,
                       TransactionHolder holder) {
        this.inner      = inner;
        this.txnManager = txnManager;
        this.holder     = holder;
    }

    @Override
    public void open() {
        currentTxn = txnManager.begin();
        holder.set(currentTxn);
        aborted = false;
        inner.open();
    }

    @Override
    public Row next() {
        try {
            return inner.next();
        } catch (RuntimeException e) {
            if (!aborted && currentTxn != null && currentTxn.isActive()) {
                aborted = true;
                txnManager.abort(currentTxn);
            }
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (!aborted && currentTxn != null && currentTxn.isActive()) {
                txnManager.commit(currentTxn);
            }
        } finally {
            inner.close();
        }
    }

    public Transaction getCurrentTxn() { return currentTxn; }
}
