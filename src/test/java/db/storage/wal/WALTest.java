package db.storage.wal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WALTest {

    @TempDir
    Path tempDir;

    private Path walPath;
    private WAL wal;

    @BeforeEach
    void setUp() throws IOException {
        walPath = tempDir.resolve("test.wal");
        wal = new WAL(walPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
    }

    // --- begin/commit writes to disk ---

    @Test
    void beginAndCommitWriteRecordsToDisk() throws IOException {
        wal.begin(1L);
        wal.commit(1L);

        List<LogRecord> records = wal.readAll();

        assertEquals(2, records.size());
        assertEquals(LogType.BEGIN,  records.get(0).getType());
        assertEquals(1L,             records.get(0).getTxnId());
        assertEquals(LogType.COMMIT, records.get(1).getType());
        assertEquals(1L,             records.get(1).getTxnId());
    }

    // --- readAll returns records in write order ---

    @Test
    void readAllAfterCommitReturnsRecordsInOrder() throws IOException {
        wal.begin(1L);
        wal.logUpdate(1L, 5, 16, new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
        wal.commit(1L);

        List<LogRecord> records = wal.readAll();

        assertEquals(3, records.size());
        assertEquals(LogType.BEGIN,  records.get(0).getType());
        assertEquals(LogType.UPDATE, records.get(1).getType());
        assertEquals(LogType.COMMIT, records.get(2).getType());
    }

    // --- UPDATE stores correct before/after images ---

    @Test
    void updateRecordStoresCorrectBeforeAndAfterImages() throws IOException {
        byte[] before = {10, 20, 30};
        byte[] after  = {40, 50, 60};

        wal.begin(1L);
        wal.logUpdate(1L, 7, 32, before, after);
        wal.commit(1L);

        List<LogRecord> records = wal.readAll();
        LogRecord update = records.stream()
            .filter(r -> r.getType() == LogType.UPDATE)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No UPDATE record found"));

        assertEquals(7,  update.getPageId());
        assertEquals(32, update.getOffset());
        assertArrayEquals(before, update.getBeforeImage());
        assertArrayEquals(after,  update.getAfterImage());
    }

    // --- LSNs are monotonically increasing ---

    @Test
    void lsnsAreMonotonicallyIncreasing() throws IOException {
        wal.begin(1L);
        wal.logUpdate(1L, 1, 0, new byte[]{1}, new byte[]{2});
        wal.logUpdate(1L, 2, 0, new byte[]{3}, new byte[]{4});
        wal.commit(1L);

        List<LogRecord> records = wal.readAll();
        assertEquals(4, records.size());

        for (int i = 0; i < records.size() - 1; i++) {
            assertTrue(
                records.get(i).getLsn() < records.get(i + 1).getLsn(),
                "LSN at index " + i + " (" + records.get(i).getLsn() +
                ") must be less than LSN at index " + (i + 1) +
                " (" + records.get(i + 1).getLsn() + ")"
            );
        }
    }

    // --- WAL persists across close/reopen ---

    @Test
    void walSurvivesCloseAndReopen() throws IOException {
        wal.begin(1L);
        wal.logUpdate(1L, 3, 16, new byte[]{0xA}, new byte[]{0xB});
        wal.commit(1L);
        wal.close();

        // Reopen — reassign so @AfterEach closes it cleanly
        wal = new WAL(walPath);

        List<LogRecord> records = wal.readAll();

        assertEquals(3, records.size());
        assertEquals(LogType.BEGIN,  records.get(0).getType());
        assertEquals(LogType.UPDATE, records.get(1).getType());
        assertEquals(LogType.COMMIT, records.get(2).getType());

        // LSN counter must not reset — next LSN > last persisted LSN
        assertTrue(
            wal.getCurrentLsn() > records.get(2).getLsn(),
            "LSN counter must continue from where it left off after reopen"
        );
    }
}
