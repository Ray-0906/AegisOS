package com.aegisos.consensus;

import com.aegisos.proto.RaftLogEntry;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaftLogTest {

    @Test
    void appendAndReadBack(@TempDir Path dir) {
        RaftLog logFile = new RaftLog(dir.resolve("log.bin"));
        assertEquals(0, logFile.lastIndex());
        logFile.append(1, "a".getBytes());
        logFile.append(1, "b".getBytes());
        assertEquals(2, logFile.lastIndex());
        assertEquals(1, logFile.lastTerm());
        assertEquals(1, logFile.termAt(1));
        assertArrayEquals("b".getBytes(), logFile.get(2).getCommand().toByteArray());
    }

    @Test
    void persistenceSurvivesReload(@TempDir Path dir) {
        Path file = dir.resolve("log.bin");
        RaftLog first = new RaftLog(file);
        first.append(1, "x".getBytes());
        first.append(2, "y".getBytes());

        RaftLog reloaded = new RaftLog(file);
        assertEquals(2, reloaded.lastIndex());
        assertEquals(2, reloaded.lastTerm());
        assertArrayEquals("x".getBytes(), reloaded.get(1).getCommand().toByteArray());
    }

    @Test
    void followerAppendResolvesConflictingSuffix(@TempDir Path dir) {
        RaftLog logFile = new RaftLog(dir.resolve("log.bin"));
        logFile.append(1, "a".getBytes());
        logFile.append(1, "b".getBytes());
        logFile.append(1, "c".getBytes());

        // Leader sends conflicting entries starting at index 2 with a higher term.
        List<RaftLogEntry> incoming = List.of(
                RaftLogEntry.newBuilder().setTerm(2).setIndex(2)
                        .setCommand(ByteString.copyFromUtf8("B")).build(),
                RaftLogEntry.newBuilder().setTerm(2).setIndex(3)
                        .setCommand(ByteString.copyFromUtf8("C")).build());
        long last = logFile.appendFromLeader(1, incoming);

        assertEquals(3, last);
        assertEquals(2, logFile.termAt(2));
        assertArrayEquals("B".getBytes(), logFile.get(2).getCommand().toByteArray());
    }

    @Test
    void upToDateComparison(@TempDir Path dir) {
        RaftLog logFile = new RaftLog(dir.resolve("log.bin"));
        logFile.append(2, "a".getBytes());
        // higher term wins
        assertTrue(logFile.isUpToDate(1, 3));
        // same term, longer or equal index wins
        assertTrue(logFile.isUpToDate(1, 2));
        // same term, shorter index loses
        assertFalse(logFile.isUpToDate(0, 2));
        // lower term loses
        assertFalse(logFile.isUpToDate(5, 1));
    }
}
