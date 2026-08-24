package com.coursistant.lms.module.assignment.service;

import com.coursistant.lms.module.assignment.dto.RubricResponse;
import com.coursistant.lms.module.assignment.entity.Assignment;
import com.coursistant.lms.module.assignment.entity.AssignmentRubricVersion;
import com.coursistant.lms.module.assignment.repository.AssignmentMapper;
import com.coursistant.lms.module.assignment.repository.AssignmentRubricVersionMapper;
import com.coursistant.lms.shared.api.ErrorType;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rubric versioning. Rubrics are PDF-only and append-only: every upload becomes a new
 * {@code version_no = max + 1} row, and "restore previous" only moves the assignment's pointer —
 * no file is ever rewritten or deleted, so grades keep referencing the version they were made with.
 */
@Service
public class AssignmentRubricService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentRubricService.class);

    @Resource
    private AssignmentMapper assignmentMapper;

    @Resource
    private AssignmentRubricVersionMapper assignmentRubricVersionMapper;

    @Resource
    private AssignmentAccessService assignmentAccessService;

    @Resource
    private AssignmentFilePolicy assignmentFilePolicy;

    @Resource
    private AssignmentStorageService assignmentStorageService;

    @Resource
    private AssignmentResponseAssembler assignmentResponseAssembler;

    @Resource
    private AssignmentAuditService assignmentAuditService;

    public RubricResponse get(HttpServletRequest request, Integer courseId, Integer assignmentId, Integer userId) {
        Assignment assignment = assignmentAccessService.requireAssignmentReadable(request, courseId, assignmentId, userId);
        return toResponse(assignment);
    }

    public ResponseEntity<InputStreamResource> download(HttpServletRequest request, Integer courseId,
                                                        Integer assignmentId, Integer userId) {
        return stream(request, courseId, assignmentId, userId, true);
    }

    public ResponseEntity<InputStreamResource> preview(HttpServletRequest request, Integer courseId,
                                                       Integer assignmentId, Integer userId) {
        return stream(request, courseId, assignmentId, userId, false);
    }

    private ResponseEntity<InputStreamResource> stream(HttpServletRequest request, Integer courseId,
                                                       Integer assignmentId, Integer userId,
                                                       boolean attachmentDisposition) {
        Assignment assignment = assignmentAccessService.requireAssignmentReadable(request, courseId, assignmentId, userId);
        AssignmentRubricVersion current = currentVersion(assignment);
        if (current == null) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId, ErrorType.RUBRIC_NOT_FOUND, null);
        }
        return assignmentStorageService.stream(current.getObjectKey(), current.getOriginalName(),
                current.getContentType(), attachmentDisposition, courseId, assignmentId, userId);
    }

    /**
     * Uploads a new rubric version and points the assignment at it. Replacing the rubric once
     * grades exist needs an explicit confirmation, because already-entered grades were made
     * against the older criteria.
     */
    @Transactional
    public RubricResponse upload(Integer courseId, Integer assignmentId, Integer userId, MultipartFile file,
                                 Boolean confirmReplaceAfterGrading) {
        Assignment assignment = assignmentAccessService.requireAssignmentConfigurable(courseId, assignmentId, userId);
        assignmentFilePolicy.validateRubricPdf(file);

        int gradedCount = assignmentMapper.countGradesByAssignmentId(assignmentId);
        boolean replacingAfterGrading = gradedCount > 0 && assignment.getCurrentRubricVersionId() != null;
        if (replacingAfterGrading && !Boolean.TRUE.equals(confirmReplaceAfterGrading)) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId,
                    ErrorType.RUBRIC_REPLACE_CONFIRM_REQUIRED,
                    "Set confirmReplaceAfterGrading=true; " + gradedCount + " grade(s) already exist");
        }

        Integer maxVersionNo = assignmentRubricVersionMapper.selectMaxVersionNo(assignmentId);
        int nextVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;

        String objectKey = assignmentFilePolicy.rubricKey(courseId, assignmentId, file.getOriginalFilename());
        assignmentStorageService.upload(objectKey, file, courseId, assignmentId, userId);

        AssignmentRubricVersion version = new AssignmentRubricVersion();
        version.setAssignmentId(assignmentId);
        version.setVersionNo(nextVersionNo);
        version.setObjectKey(objectKey);
        version.setOriginalName(assignmentFilePolicy.sanitizeFilename(file.getOriginalFilename()));
        version.setContentType(file.getContentType() == null ? "application/pdf" : file.getContentType());
        version.setSizeBytes(file.getSize());
        version.setUploadedBy(userId);
        assignmentRubricVersionMapper.insert(version);

        assignmentMapper.updateCurrentRubricVersionId(assignmentId, version.getId());

        assignmentAuditService.write(courseId, assignmentId, userId, AssignmentAuditService.RUBRIC_UPLOADED,
                Map.of("rubricVersionId", version.getId(), "versionNo", nextVersionNo));
        RubricResponse response = toResponse(requireAssignment(courseId, assignmentId, userId));
        if (replacingAfterGrading) {
            assignmentAuditService.write(courseId, assignmentId, userId,
                    AssignmentAuditService.RUBRIC_REPLACED_AFTER_GRADING,
                    Map.of("rubricVersionId", version.getId(), "gradedCount", gradedCount));
            response.setGradedAgainstPreviousRubricCount(gradedCount);
        }
        return response;
    }

    /**
     * Moves the pointer back to the next-lower rubric version. Nothing is copied or deleted.
     * When grades already exist, requires the same confirm gate as replace-after-grading.
     */
    @Transactional
    public RubricResponse restorePrevious(Integer courseId, Integer assignmentId, Integer userId,
                                          Boolean confirmReplaceAfterGrading) {
        Assignment assignment = assignmentAccessService.requireAssignmentConfigurable(courseId, assignmentId, userId);
        AssignmentRubricVersion current = currentVersion(assignment);
        if (current == null) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId, ErrorType.RUBRIC_NOT_FOUND, null);
        }
        AssignmentRubricVersion previous = previousVersion(assignmentId, current.getVersionNo());
        if (previous == null) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId, ErrorType.RUBRIC_NO_PREVIOUS_VERSION, null);
        }

        int gradedCount = assignmentMapper.countGradesByAssignmentId(assignmentId);
        boolean restoringAfterGrading = gradedCount > 0;
        if (restoringAfterGrading && !Boolean.TRUE.equals(confirmReplaceAfterGrading)) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId,
                    ErrorType.RUBRIC_REPLACE_CONFIRM_REQUIRED,
                    "Set confirmReplaceAfterGrading=true; " + gradedCount + " grade(s) already exist");
        }

        assignmentMapper.updateCurrentRubricVersionId(assignmentId, previous.getId());
        assignmentAuditService.write(courseId, assignmentId, userId, AssignmentAuditService.RUBRIC_RESTORED_PREVIOUS,
                Map.of("fromVersionNo", current.getVersionNo(), "toVersionNo", previous.getVersionNo()));
        if (restoringAfterGrading) {
            assignmentAuditService.write(courseId, assignmentId, userId,
                    AssignmentAuditService.RUBRIC_RESTORED_AFTER_GRADING,
                    Map.of("fromVersionNo", current.getVersionNo(), "toVersionNo", previous.getVersionNo(),
                            "gradedCount", gradedCount));
        }

        RubricResponse response = toResponse(requireAssignment(courseId, assignmentId, userId));
        if (restoringAfterGrading) {
            response.setGradedAgainstPreviousRubricCount(gradedCount);
        }
        return response;
    }

    /**
     * Shared with the grading view so a grader always sees the rubric in force.
     */
    public RubricResponse toResponse(Assignment assignment) {
        List<AssignmentRubricVersion> versions = versions(assignment.getId());
        AssignmentRubricVersion current = currentVersion(assignment);
        boolean canRestore = current != null && previousVersion(versions, current.getVersionNo()) != null;
        return assignmentResponseAssembler.toRubricResponse(assignment.getCourseId(), assignment.getId(),
                current, versions.size(), canRestore);
    }

    private AssignmentRubricVersion currentVersion(Assignment assignment) {
        if (assignment.getCurrentRubricVersionId() == null) {
            return null;
        }
        return assignmentRubricVersionMapper.selectById(assignment.getCurrentRubricVersionId());
    }

    private AssignmentRubricVersion previousVersion(Integer assignmentId, Integer currentVersionNo) {
        return previousVersion(versions(assignmentId), currentVersionNo);
    }

    private AssignmentRubricVersion previousVersion(List<AssignmentRubricVersion> versionsDesc, Integer currentVersionNo) {
        if (currentVersionNo == null) {
            return null;
        }
        for (AssignmentRubricVersion version : versionsDesc) {
            if (version.getVersionNo() != null && version.getVersionNo() < currentVersionNo) {
                return version;
            }
        }
        return null;
    }

    private List<AssignmentRubricVersion> versions(Integer assignmentId) {
        List<AssignmentRubricVersion> versions =
                assignmentRubricVersionMapper.selectByAssignmentIdOrderByVersionDesc(assignmentId);
        if (versions == null) {
            throw AssignmentErrors.fail(log, null, assignmentId, null, ErrorType.INTERNAL_ERROR,
                    "Rubric version query returned null");
        }
        return versions;
    }

    private Assignment requireAssignment(Integer courseId, Integer assignmentId, Integer userId) {
        Assignment assignment = assignmentMapper.selectByCourseIdAndId(courseId, assignmentId);
        if (assignment == null) {
            throw AssignmentErrors.fail(log, courseId, assignmentId, userId, ErrorType.ASSIGNMENT_NOT_FOUND, null);
        }
        return assignment;
    }
}
