package db.bench;

import db.storage.wal.WAL;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for Write-Ahead Log throughput.
 *
 * Key measurements:
 *   logUpdate       — cost of buffering one UPDATE record (no fsync)
 *   fullCommitCycle — begin + update + commit including fsync
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class WALBench {

    private WAL  wal;
    private Path tempFile;

    private static final byte[] BEFORE = new byte[64];
    private static final byte[] AFTER  = new byte[64];
    private long txnId = 1L;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempFile = Files.createTempFile("wal_bench", ".wal");
        wal      = new WAL(tempFile);
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        wal.close();
        Files.deleteIfExists(tempFile);
    }

    @Benchmark
    public void logUpdate() throws IOException {
        wal.logUpdate(txnId++, 1, 16, BEFORE, AFTER);
    }

    @Benchmark
    public void fullCommitCycle() throws IOException {
        long id = txnId++;
        wal.begin(id);
        wal.logUpdate(id, 1, 16, BEFORE, AFTER);
        wal.commit(id);
    }

    public static void main(String[] args) throws Exception {
        new Runner(new OptionsBuilder()
            .include(WALBench.class.getSimpleName())
            .build()
        ).run();
    }
}
