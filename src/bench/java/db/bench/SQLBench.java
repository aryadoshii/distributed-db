package db.bench;

import db.server.Catalog;
import db.sql.executor.Operator;
import db.sql.executor.Planner;
import db.sql.executor.Row;
import db.sql.parser.Parser;
import db.sql.ast.Statement;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import db.txn.TransactionManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end SQL benchmark — parse → plan → execute → B-tree.
 * Measures real throughput through all layers.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SQLBench {

    private Planner     planner;
    private DiskManager diskManager;
    private Path        tempFile;
    private int         insertKey = 1_000_000;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        tempFile    = Files.createTempFile("sql_bench", ".db");
        diskManager = new DiskManager(tempFile);
        BufferPool         bp  = new BufferPool(512, diskManager);
        Catalog            cat = new Catalog();
        TransactionManager tm  = new TransactionManager();
        planner = new Planner(cat, bp, tm);

        runSQL("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(64))");
        for (int i = 1; i <= 10_000; i++) {
            runSQL("INSERT INTO users (id, name) VALUES (" + i + ", 'user" + i + "')");
        }
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        diskManager.close();
        Files.deleteIfExists(tempFile);
    }

    @Benchmark
    public List<Row> selectAll() throws Exception {
        return runSQL("SELECT * FROM users");
    }

    @Benchmark
    public List<Row> selectWithFilter() throws Exception {
        return runSQL("SELECT * FROM users WHERE id = 5000");
    }

    @Benchmark
    public List<Row> insertOne() throws Exception {
        int key = insertKey++;
        return runSQL("INSERT INTO users (id, name) VALUES (" + key + ", 'bench_user')");
    }

    private List<Row> runSQL(String sql) throws Exception {
        Statement stmt = Parser.parseSQL(sql);
        Operator  op   = planner.plan(stmt);
        List<Row> rows = new ArrayList<>();
        op.open();
        try {
            Row row;
            while ((row = op.next()) != null) rows.add(row);
        } finally {
            op.close();
        }
        return rows;
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
            .include(SQLBench.class.getSimpleName())
            .build()
        ).run();
    }
}
