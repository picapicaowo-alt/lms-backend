package com.coursistant.lms.module.quiz.dto.authoring;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Data
@Schema(name = "QuizResponse", description = "Quiz metadata for course members")
public class QuizResponse {
    @Schema(description = "Quiz id", example = "3")
    private Integer id;
    @Schema(description = "Course id", example = "1")
    private Integer courseId;
    @Schema(description = "Title", example = "Week 1 Quiz")
    private String title;
    @Schema(description = "Instructions shown to students")
    private String instructions;
    @Schema(description = "Window open instant (UTC)")
    private Instant opensAtUtc;
    @Schema(description = "Window open wall-clock in course timezone")
    private LocalDateTime opensAtLocal;
    @Schema(description = "Window close instant (UTC)")
    private Instant closesAtUtc;
    @Schema(description = "Window close wall-clock in course timezone")
    private LocalDateTime closesAtLocal;
    @Schema(description = "IANA timezone of wall-clock fields", example = "America/Los_Angeles")
    private String timezone;
    @Schema(description = "Per-attempt time limit in seconds; null if unlimited", example = "3600")
    private Integer timeLimitSeconds;
    @Schema(description = "Max attempts allowed per student", example = "1")
    private Integer attemptsAllowed;
    @Schema(description = "When scores/answers become visible", example = "AfterRelease",
            allowableValues = {"AfterRelease", "InstantAutoScore"})
    private String resultVisibility;
    @Schema(description = "Lifecycle state", example = "Draft", allowableValues = {"Draft", "Published"})
    private String state;
    @Schema(description = "True when now is within [opensAt, closesAt). Independent of Draft/Published.")
    private Boolean windowOpen;
    @Schema(description = "Optimistic concurrency version", example = "1")
    private Integer version;
    @Schema(description = "Sum of question points", example = "10.0")
    private BigDecimal totalPoints;
    @Schema(description = "Number of questions", example = "5")
    private Integer questionCount;
    @Schema(description = "Whether any student attempt exists")
    private Boolean hasAttempts;
    @Schema(description = "Created at (UTC)")
    private Instant createdAt;
    @Schema(description = "Updated at (UTC)")
    private Instant updatedAt;
}
