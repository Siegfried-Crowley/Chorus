package org.chorus.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SnowflakeGenerator {
    private final long epoch = 1700000000000L;
    private final int workerId;
    private long lastMs = 0;
    private int sequence = 0;

    public SnowflakeGenerator(@Value("${app.snowflake.worker-id:1}") int workerId) {
        this.workerId = workerId & 1023;
    }

    public synchronized long nextId() {
        long nowMs = Instant.now().toEpochMilli();
        if (nowMs == lastMs) {
            sequence = (sequence + 1) & 4095;
            if (sequence == 0) {
                while (nowMs <= lastMs) {
                    nowMs = Instant.now().toEpochMilli();
                }
            }
        } else {
            sequence = 0;
        }
        lastMs = nowMs;
        return ((nowMs - epoch) << 22) | ((long) workerId << 12) | sequence;
    }
}
