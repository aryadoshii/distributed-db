package db.sql.executor;

public interface Operator {
    void open();
    Row next();
    void close();
}
