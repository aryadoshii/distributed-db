package db.bench;

import db.storage.btree.BTree;
import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for B-tree storage engine operations.
 *
 * Run with: mvn exec:java -Dexec.mainClass=db.bench.BTreeBench
 *
 * Warmup:      3 iterations × 2 seconds
 * Measurement: 5 iterations × 2 seconds
 * Forks:       1 JVM fork (fresh JVM per benchmark)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class BTreeBench {

    private BTree       bTree;
    private BufferPool  bufferPool;
    private DiskManager diskManager;
    private Path        tempFile;

    private int insertKey = 0;

    private static final int    SEARCH_KEY = 25_000;
    private static final byte[] VALUE      = ByteBuffer.allocate(4).putInt(0).array();

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempFile    = Files.createTempFile("btree_bench", ".db");
        diskManager = new DiskManager(tempFile);
        bufferPool  = new BufferPool(1024, diskManager);
        bTree       = new BTree(bufferPool);

        for (int i = 0; i < 50_000; i++) {
            bTree.insert(i, ByteBuffer.allocate(4).putInt(i).array());
        }
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        diskManager.close();
        Files.deleteIfExists(tempFile);
    }

    @Benchmark
    public void insertSequential() throws IOException {
        bTree.insert(insertKey++, VALUE);
    }

    @Benchmark
    public byte[] pointLookup() throws IOException {
        return bTree.search(SEARCH_KEY);
    }

    @Benchmark
    public List<byte[]> rangeScan100() throws IOException {
        return bTree.rangeScan(SEARCH_KEY, SEARCH_KEY + 100);
    }

    @Benchmark
    public List<byte[]> rangeScan1000() throws IOException {
        return bTree.rangeScan(SEARCH_KEY, SEARCH_KEY + 1000);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(BTreeBench.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
