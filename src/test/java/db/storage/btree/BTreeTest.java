package db.storage.btree;

import db.storage.buffer.BufferPool;
import db.storage.page.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BTreeTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool pool;
    private BTree tree;

    @BeforeEach
    void setUp() throws IOException {
        diskManager = new DiskManager(tempDir.resolve("btree.db"));
        pool = new BufferPool(50, diskManager);
        tree = new BTree(pool);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    private static byte[] encode(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static int decode(byte[] b) {
        return ByteBuffer.wrap(b).getInt();
    }

    // --- single key ---

    @Test
    void insertSingleKeyAndSearchReturnsCorrectValue() throws IOException {
        tree.insert(42, encode(42));

        byte[] result = tree.search(42);

        assertNotNull(result);
        assertEquals(42, decode(result));
    }

    // --- 1000 random keys ---

    @Test
    void insert1000KeysInRandomOrderSearchAllReturnsCorrectValues() throws IOException {
        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) keys.add(i);
        Collections.shuffle(keys, new Random(42));

        for (int k : keys) {
            tree.insert(k, encode(k));
        }

        for (int i = 1; i <= 1000; i++) {
            byte[] result = tree.search(i);
            assertNotNull(result, "Expected non-null result for key " + i);
            assertEquals(i, decode(result), "Wrong value for key " + i);
        }
    }

    // --- range scan ---

    @Test
    void rangeScanReturnsExactlyRightKeys() throws IOException {
        for (int i = 1; i <= 300; i++) {
            tree.insert(i, encode(i));
        }

        List<byte[]> results = tree.rangeScan(100, 200);

        assertEquals(101, results.size(), "Range [100..200] should return exactly 101 values");

        Set<Integer> found = new HashSet<>();
        for (byte[] v : results) found.add(decode(v));

        for (int i = 100; i <= 200; i++) {
            assertTrue(found.contains(i), "Key " + i + " missing from range scan result");
        }
    }

    // --- delete ---

    @Test
    void deleteKeyThenSearchReturnsNull() throws IOException {
        tree.insert(7, encode(7));
        assertNotNull(tree.search(7));

        boolean deleted = tree.delete(7);

        assertTrue(deleted, "delete() should return true when key exists");
        assertNull(tree.search(7), "search() should return null after key is deleted");
    }

    // --- root split ---

    @Test
    void insertOrderPlusOneKeysForceRootSplitAllStillSearchable() throws IOException {
        int count = BTreeNode.ORDER + 1;  // 201 — guaranteed to split the root

        for (int i = 1; i <= count; i++) {
            tree.insert(i, encode(i));
        }

        for (int i = 1; i <= count; i++) {
            byte[] result = tree.search(i);
            assertNotNull(result, "Key " + i + " missing after root split");
            assertEquals(i, decode(result), "Wrong value for key " + i + " after root split");
        }
    }
}
