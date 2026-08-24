package com.coursistant.lms.module.course.content.material.service;

import com.coursistant.lms.module.course.content.CourseContentAccessService;
import com.coursistant.lms.module.course.content.CourseContentFilePolicy;
import com.coursistant.lms.module.course.content.material.dto.MaterialResponse;
import com.coursistant.lms.module.course.content.material.dto.MoveMaterialRequest;
import com.coursistant.lms.module.course.content.material.dto.RenameMaterialRequest;
import com.coursistant.lms.module.course.content.material.dto.ReorderMaterialsRequest;
import com.coursistant.lms.module.course.content.material.entity.CourseMaterial;
import com.coursistant.lms.module.course.content.material.repository.CourseMaterialMapper;
import com.coursistant.lms.module.course.content.week.entity.CourseWeek;
import com.coursistant.lms.module.course.course.service.CourseAuditActions;
import com.coursistant.lms.module.course.course.service.CourseAuditService;
import com.coursistant.lms.module.course.storage.entity.UploadOperation;
import com.coursistant.lms.module.course.storage.service.MinioOutboxService;
import com.coursistant.lms.module.course.storage.service.UploadOperationService;
import com.coursistant.lms.module.file.service.MinIOService;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import com.coursistant.lms.shared.security.ActorContext;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CourseMaterialService {

    private static final Logger log = LoggerFactory.getLogger(CourseMaterialService.class);

    private static final String TYPE_FILE = "FILE";
    private static final String TYPE_LINK = "LINK";
    private static final int MAX_DISPLAY_NAME_LENGTH = 255;

    @Resource
    private CourseMaterialMapper courseMaterialMapper;

    @Resource
    private CourseContentAccessService courseContentAccessService;

    @Resource
    private CourseContentFilePolicy courseContentFilePolicy;

    @Resource
    private MinIOService minIOService;

    @Resource
    private MaterialResponseAssembler materialResponseAssembler;

    @Resource
    private MinioOutboxService minioOutboxService;

    @Resource
    private UploadOperationService uploadOperationService;

    @Resource
    private CourseAuditService courseAuditService;

    @Transactional
    public List<MaterialResponse> create(ActorContext actor, Integer courseId, Integer weekId,
                                          MultipartFile[] files, String linkUrl, String linkDisplayName,
                                          HttpServletRequest request) {
        courseContentAccessService.requireMaterialUpload(actor, courseId, weekId);

        boolean hasFiles = files != null && files.length > 0
                && java.util.Arrays.stream(files).anyMatch(f -> f != null && !f.isEmpty());
        boolean hasLink = linkUrl != null && !linkUrl.isBlank();
        if (!hasFiles && !hasLink) {
            throw new ApiException(ErrorType.PARAM_MISSING, "At least one file or a linkUrl is required");
        }

        String idemKey = request != null ? request.getHeader("Idempotency-Key") : null;
        String fingerprint = request != null ? (String) request.getAttribute("idem.fingerprint") : null;
        UploadOperation uploadOp = null;
        if (idemKey != null && !idemKey.isBlank() && fingerprint != null) {
            String routeId = "POST /v2/courses/{courseId}/weeks/{weekId}/materials";
            uploadOp = uploadOperationService.createOrResume(actor, idemKey, routeId, fingerprint, courseId);
            // READY operations are normally replayed by Redis before the controller runs.
        }

        int nextOrder = nextOrderPosition(weekId);
        List<CourseMaterial> created = new ArrayList<>();
        String stagingPrefix = uploadOp == null ? null : ("staging/" + uploadOp.getId() + "/");

        try {
            if (hasFiles) {
                for (MultipartFile file : files) {
                    if (file == null || file.isEmpty()) {
                        continue;
                    }
                    courseContentFilePolicy.validateFile(file);
                    String originalFilename = file.getOriginalFilename();
                    String extension = courseContentFilePolicy.extensionOf(originalFilename);
                    String objectKey = courseContentFilePolicy.buildObjectKey(
                            stagingPrefix != null
                                    ? stagingPrefix + "materials"
                                    : "course-content/" + courseId + "/weeks/" + weekId + "/materials",
                            originalFilename);

                    try {
                        minIOService.uploadFile(objectKey, file, courseContentFilePolicy.bucket());
                    } catch (Exception e) {
                        log.warn("Failed to upload course material to MinIO: key={}", objectKey, e);
                        throw new ApiException(ErrorType.INTERNAL_SERVER_ERROR, "Failed to upload file");
                    }

                    String finalKey = objectKey;
                    if (stagingPrefix != null) {
                        finalKey = courseContentFilePolicy.buildObjectKey(
                                "course-content/" + courseId + "/weeks/" + weekId + "/materials", originalFilename);
                        try {
                            minIOService.copyObject(courseContentFilePolicy.bucket(), objectKey, finalKey);
                            minioOutboxService.enqueueDelete(
                                    courseContentFilePolicy.bucket(), objectKey, courseId, uploadOp.getId());
                        } catch (Exception e) {
                            minioOutboxService.enqueueAbortStagingIndependent(
                                    courseContentFilePolicy.bucket(), objectKey, courseId, uploadOp.getId());
                            throw new ApiException(ErrorType.INTERNAL_SERVER_ERROR, "Failed to commit uploaded file");
                        }
                    }

                    CourseMaterial material = new CourseMaterial();
                    material.setWeekId(weekId);
                    material.setCourseId(courseId);
                    material.setMaterialType(TYPE_FILE);
                    material.setDisplayName(trimToLength(baseName(originalFilename)));
                    material.setOrderPosition(nextOrder++);
                    material.setOriginalFilename(originalFilename);
                    material.setContentType(file.getContentType());
                    material.setExtension(extension);
                    material.setSizeBytes(file.getSize());
                    material.setObjectKey(finalKey);
                    material.setUploadedBy(actor.getActorId());
                    courseMaterialMapper.insert(material);
                    created.add(courseMaterialMapper.selectById(material.getId()));
                }
            }

            if (hasLink) {
                String normalizedUrl = validateAndNormalizeUrl(linkUrl);
                String displayName = linkDisplayName != null && !linkDisplayName.isBlank()
                        ? linkDisplayName.trim()
                        : normalizedUrl;

                CourseMaterial material = new CourseMaterial();
                material.setWeekId(weekId);
                material.setCourseId(courseId);
                material.setMaterialType(TYPE_LINK);
                material.setDisplayName(trimToLength(displayName));
                material.setOrderPosition(nextOrder);
                material.setLinkUrl(normalizedUrl);
                material.setUploadedBy(actor.getActorId());
                courseMaterialMapper.insert(material);
                created.add(courseMaterialMapper.selectById(material.getId()));
            }

            if (uploadOp != null) {
                uploadOperationService.markReady(uploadOp.getId());
            }
            courseAuditService.write(actor, courseId, null, CourseAuditActions.MATERIAL_CREATED,
                    CourseAuditActions.TARGET_MATERIAL,
                    created.isEmpty() ? null : created.get(0).getId(),
                    null, Map.of("count", created.size()), idemKey);
            return materialResponseAssembler.toResponses(created);
        } catch (RuntimeException e) {
            if (uploadOp != null) {
                uploadOperationService.markFailed(uploadOp.getId());
            }
            throw e;
        }
    }

    public MaterialResponse rename(ActorContext actor, Integer courseId, Integer weekId, Integer materialId,
                                    RenameMaterialRequest request) {
        courseContentAccessService.requireMaterialManage(actor, courseId, weekId);
        CourseMaterial material = requireMaterialInWeek(weekId, materialId);

        if (request == null || request.getDisplayName() == null || request.getDisplayName().isBlank()) {
            throw new ApiException(ErrorType.PARAM_MISSING, "displayName is required");
        }
        String displayName = trimToLength(request.getDisplayName().trim());

        CourseMaterial patch = new CourseMaterial();
        patch.setId(materialId);
        patch.setDisplayName(displayName);
        courseMaterialMapper.updateById(patch);

        return materialResponseAssembler.toResponse(courseMaterialMapper.selectById(materialId));
    }

    @Transactional
    public List<MaterialResponse> reorder(ActorContext actor, Integer courseId, Integer weekId,
                                           ReorderMaterialsRequest request) {
        courseContentAccessService.requireMaterialManage(actor, courseId, weekId);
        if (request == null || request.getMaterialIds() == null) {
            throw new ApiException(ErrorType.PARAM_MISSING, "materialIds is required");
        }

        List<CourseMaterial> existing = courseMaterialMapper.selectByWeekId(weekId);
        Set<Integer> existingIds = existing.stream().map(CourseMaterial::getId).collect(Collectors.toSet());
        Set<Integer> requestedIds = new HashSet<>(request.getMaterialIds());

        if (!existingIds.equals(requestedIds) || existingIds.size() != request.getMaterialIds().size()) {
            throw new ApiException(ErrorType.BAD_REQUEST, "materialIds must contain exactly the week's current materials");
        }

        int position = 0;
        for (Integer materialId : request.getMaterialIds()) {
            courseMaterialMapper.updateOrderPosition(materialId, position++);
        }

        return materialResponseAssembler.toResponses(courseMaterialMapper.selectByWeekId(weekId));
    }

    @Transactional
    public MaterialResponse move(ActorContext actor, Integer courseId, Integer weekId, Integer materialId,
                                  MoveMaterialRequest request) {
        courseContentAccessService.requireMaterialManage(actor, courseId, weekId);
        CourseMaterial material = requireMaterialInWeek(weekId, materialId);

        if (request == null || request.getTargetWeekId() == null) {
            throw new ApiException(ErrorType.PARAM_MISSING, "targetWeekId is required");
        }
        Integer targetWeekId = request.getTargetWeekId();

        if (targetWeekId.equals(weekId)) {
            return materialResponseAssembler.toResponse(material);
        }

        courseContentAccessService.requireMaterialManage(actor, courseId, targetWeekId);
        int newOrder = nextOrderPosition(targetWeekId);
        courseMaterialMapper.updateWeekId(materialId, targetWeekId, newOrder);

        return materialResponseAssembler.toResponse(courseMaterialMapper.selectById(materialId));
    }

    @Transactional
    public void delete(ActorContext actor, Integer courseId, Integer weekId, Integer materialId) {
        CourseMaterial material = requireMaterialInWeek(weekId, materialId);
        courseContentAccessService.requireMaterialDelete(actor, courseId, weekId, material);

        courseMaterialMapper.deleteById(materialId);

        if (TYPE_FILE.equals(material.getMaterialType()) && material.getObjectKey() != null) {
            // Same TX: business delete + outbox DELETE; worker removes object after commit.
            minioOutboxService.enqueueDelete(
                    courseContentFilePolicy.bucket(), material.getObjectKey(), courseId, null);
        }
    }

    public ResponseEntity<InputStreamResource> preview(ActorContext actor, Integer courseId, Integer weekId,
                                                         Integer materialId) {
        CourseWeek week = courseContentAccessService.requireWeekReadable(actor, courseId, weekId);
        CourseMaterial material = requireMaterialInWeek(week.getId(), materialId);

        if (!TYPE_FILE.equals(material.getMaterialType())) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Only file materials can be previewed");
        }
        if (!courseContentFilePolicy.isPreviewable(material.getContentType(), material.getExtension())) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Material type is not previewable");
        }

        try {
            InputStream stream = minIOService.downloadFile(material.getObjectKey(), courseContentFilePolicy.bucket());
            MediaType mediaType = resolveMediaType(material.getContentType());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mediaType.getType() + "/" + mediaType.getSubtype())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sanitizeHeaderValue(material.getDisplayName()) + "\"")
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            log.warn("Failed to stream course material preview: key={}", material.getObjectKey(), e);
            throw new ApiException(ErrorType.INTERNAL_SERVER_ERROR, "Failed to load preview");
        }
    }

    public ResponseEntity<?> download(ActorContext actor, Integer courseId, Integer weekId,
                                       Integer materialId) {
        CourseWeek week = courseContentAccessService.requireWeekReadable(actor, courseId, weekId);
        CourseMaterial material = requireMaterialInWeek(week.getId(), materialId);

        if (TYPE_LINK.equals(material.getMaterialType())) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(material.getLinkUrl()))
                    .build();
        }

        try {
            InputStream stream = minIOService.downloadFile(material.getObjectKey(), courseContentFilePolicy.bucket());
            MediaType mediaType = resolveMediaType(material.getContentType());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, mediaType.getType() + "/" + mediaType.getSubtype())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + sanitizeHeaderValue(material.getOriginalFilename()) + "\"")
                    .body(new InputStreamResource(stream));
        } catch (Exception e) {
            log.warn("Failed to stream course material download: key={}", material.getObjectKey(), e);
            throw new ApiException(ErrorType.INTERNAL_SERVER_ERROR, "Failed to download file");
        }
    }

    private int nextOrderPosition(Integer weekId) {
        Integer max = courseMaterialMapper.selectMaxOrderPosition(weekId);
        return max == null ? 0 : max + 1;
    }

    private CourseMaterial requireMaterialInWeek(Integer weekId, Integer materialId) {
        if (materialId == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Material id is required");
        }
        CourseMaterial material = courseMaterialMapper.selectById(materialId);
        if (material == null || !weekId.equals(material.getWeekId())) {
            throw new ApiException(ErrorType.MATERIAL_NOT_FOUND);
        }
        return material;
    }

    private String validateAndNormalizeUrl(String linkUrl) {
        String trimmed = linkUrl.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ApiException(ErrorType.BAD_REQUEST, "linkUrl must start with http:// or https://");
        }
        if (trimmed.length() > 2048) {
            throw new ApiException(ErrorType.BAD_REQUEST, "linkUrl is too long");
        }
        return trimmed;
    }

    private String trimToLength(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_DISPLAY_NAME_LENGTH ? value.substring(0, MAX_DISPLAY_NAME_LENGTH) : value;
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String baseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "Untitled file";
        }
        String normalized = filename.replace("\\", "/");
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.isBlank() ? "Untitled file" : name;
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return "file";
        }
        return value.replace("\"", "'");
    }
}
