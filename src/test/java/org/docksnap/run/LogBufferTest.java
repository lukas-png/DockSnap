package org.docksnap.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogBufferTest {

    private LogBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new LogBuffer();
    }

    @Test
    void tail_emptyRun_returnsEmptyList() {
        assertTrue(buffer.tail("unknown-run", 10).isEmpty());
    }

    @Test
    void appendAndTail_returnsLines() {
        buffer.append("run1", "INFO", "starting backup");
        buffer.append("run1", "INFO", "done");

        List<String> lines = buffer.tail("run1", 10);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("[INFO]"));
        assertTrue(lines.get(0).contains("starting backup"));
        assertTrue(lines.get(1).contains("done"));
    }

    @Test
    void tail_maxLimitsOutput() {
        for (int i = 0; i < 10; i++) {
            buffer.append("run1", "INFO", "line " + i);
        }

        List<String> lines = buffer.tail("run1", 3);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).contains("line 9"), "tail should return the last N lines");
        assertTrue(lines.get(0).contains("line 7"));
    }

    @Test
    void tail_isolatesByRunId() {
        buffer.append("run1", "INFO", "run1-msg");
        buffer.append("run2", "INFO", "run2-msg");

        List<String> run1Lines = buffer.tail("run1", 10);
        assertEquals(1, run1Lines.size());
        assertTrue(run1Lines.get(0).contains("run1-msg"));

        List<String> run2Lines = buffer.tail("run2", 10);
        assertEquals(1, run2Lines.size());
        assertTrue(run2Lines.get(0).contains("run2-msg"));
    }

    @Test
    void circularBuffer_capsAt2000Lines() {
        for (int i = 0; i < 2100; i++) {
            buffer.append("run1", "INFO", "line " + i);
        }

        List<String> lines = buffer.tail("run1", 2100);
        assertEquals(2000, lines.size());
        assertTrue(lines.get(1999).contains("line 2099"), "last appended line should be present");
        assertFalse(lines.get(0).contains("line 0"), "oldest lines should have been evicted");
    }

    @Test
    void appendFormatsLevelInBrackets() {
        buffer.append("r1", "ERROR", "something failed");
        String line = buffer.tail("r1", 1).get(0);
        assertTrue(line.contains("[ERROR]"));
        assertTrue(line.contains("something failed"));
    }
}