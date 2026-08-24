package com.coursistant.lms.module.quiz.service;

import com.coursistant.lms.shared.util.TimeZoneUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Component
public class QuizTimeSupport {

    public LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    public LocalDateTime toUtc(LocalDateTime localWallClock, ZoneId zone) {
        if (localWallClock == null) {
            return null;
        }
        return TimeZoneUtils.toUtcLocalDateTime(localWallClock, zone == null ? ZoneOffset.UTC : zone);
    }

    public LocalDateTime toZone(LocalDateTime utc, ZoneId zone) {
        if (utc == null || zone == null) {
            return null;
        }
        return TimeZoneUtils.fromUtcLocalDateTime(utc, zone);
    }

    public Instant toInstant(LocalDateTime utc) {
        return TimeZoneUtils.toInstant(utc);
    }

    public LocalDateTime computeDeadline(LocalDateTime startedAt, LocalDateTime closesAt, Integer timeLimitSeconds) {
        if (timeLimitSeconds == null || timeLimitSeconds <= 0) {
            return closesAt;
        }
        LocalDateTime limitEnd = startedAt.plusSeconds(timeLimitSeconds);
        return limitEnd.isBefore(closesAt) ? limitEnd : closesAt;
    }

    /** Inclusive of opensAt, exclusive of closesAt, using the stored UTC wall-clock values. */
    public boolean isWindowOpen(LocalDateTime opensAt, LocalDateTime closesAt) {
        return isWindowOpen(opensAt, closesAt, nowUtc());
    }

    public boolean isWindowOpen(LocalDateTime opensAt, LocalDateTime closesAt, LocalDateTime nowUtc) {
        if (opensAt == null || closesAt == null || nowUtc == null) {
            return false;
        }
        return !nowUtc.isBefore(opensAt) && nowUtc.isBefore(closesAt);
    }
}
