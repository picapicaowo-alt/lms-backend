package com.coursistant.lms.module.course.announcement.service;

import com.coursistant.lms.module.course.announcement.dto.AnnouncementResponse;
import com.coursistant.lms.module.course.announcement.dto.AnnouncementSummaryResponse;
import com.coursistant.lms.module.course.announcement.dto.CreateAnnouncementRequest;
import com.coursistant.lms.module.course.announcement.dto.RecentAnnouncementResponse;
import com.coursistant.lms.module.course.announcement.dto.UpdateAnnouncementRequest;
import com.coursistant.lms.module.course.announcement.entity.CourseAnnouncement;
import com.coursistant.lms.module.course.announcement.entity.CourseAnnouncementRead;
import com.coursistant.lms.module.course.announcement.repository.CourseAnnouncementMapper;
import com.coursistant.lms.module.course.announcement.repository.CourseAnnouncementReadMapper;
import com.coursistant.lms.module.course.course.entity.Course;
import com.coursistant.lms.module.course.course.repository.CourseMapper;
import com.coursistant.lms.module.course.enrollment.service.CoursePermissionService;
import com.coursistant.lms.module.interaction.notification.dto.NotificationDispatchPayload;
import com.coursistant.lms.module.interaction.notification.enums.NotificationType;
import com.coursistant.lms.module.interaction.notification.enums.SubjectType;
import com.coursistant.lms.module.interaction.notification.service.NotificationCommitHook;
import com.coursistant.lms.module.interaction.notification.service.NotificationMessageFactory;
import com.coursistant.lms.module.interaction.notification.service.NotificationRecipientResolver;
import com.coursistant.lms.module.interaction.notification.service.NotificationService;
import com.coursistant.lms.module.interaction.notification.service.NotificationTimeSupport;
import com.coursistant.lms.module.user.account.entity.User;
import com.coursistant.lms.module.user.account.repository.UserMapper;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class CourseAnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(CourseAnnouncementService.class);
    private static final String STATE_ARCHIVED = "Archived";
    private static final int LIST_LIMIT = 100;
    private static final int RECENT_DEFAULT = 10;
    private static final int RECENT_MAX = 50;
    private static final int TITLE_MAX = 200;

    @Resource
    private CourseAnnouncementMapper courseAnnouncementMapper;

    @Resource
    private CourseAnnouncementReadMapper courseAnnouncementReadMapper;

    @Resource
    private CourseMapper courseMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private CoursePermissionService coursePermissionService;

    @Resource
    private NotificationRecipientResolver notificationRecipientResolver;

    @Resource
    private NotificationMessageFactory notificationMessageFactory;

    @Resource
    private NotificationCommitHook notificationCommitHook;

    @Resource
    private NotificationTimeSupport notificationTimeSupport;

    @Resource
    private NotificationService notificationService;

    public List<AnnouncementSummaryResponse> listByCourse(Integer courseId, Integer userId) {
        requireCourseNotArchived(courseId);
        return courseAnnouncementMapper.selectSummariesByCourseId(courseId, userId, LIST_LIMIT);
    }

    @Transactional
    public AnnouncementResponse getById(Integer courseId, Integer announcementId, Integer userId) {
        requireCourseNotArchived(courseId);
        CourseAnnouncement announcement = requireAnnouncementInCourse(courseId, announcementId);
        markAnnouncementRead(announcementId, userId);
        return toDetail(announcement, true);
    }

    @Transactional
    public AnnouncementResponse create(Integer courseId, Integer authorUserId, CreateAnnouncementRequest request) {
        Course course = requireCourseNotArchived(courseId);
        coursePermissionService.requireCanPostAnnouncements(courseId, authorUserId);
        if (request == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Request body is required");
        }
        String title = requireNonBlank(request.getTitle(), "title");
        String body = requireNonBlank(request.getBody(), "body");
        if (title.length() > TITLE_MAX) {
            throw new ApiException(ErrorType.BAD_REQUEST, "title must be at most " + TITLE_MAX + " characters");
        }

        LocalDateTime now = LocalDateTime.now();
        CourseAnnouncement announcement = new CourseAnnouncement();
        announcement.setCourseId(courseId);
        announcement.setTitle(title);
        announcement.setBodyHtml(body);
        announcement.setAuthorUserId(authorUserId);
        announcement.setAuthorName(resolveAuthorName(authorUserId));
        announcement.setPostedAt(now);
        courseAnnouncementMapper.insert(announcement);

        CourseAnnouncement persisted = courseAnnouncementMapper.selectById(announcement.getId());
        List<Integer> recipientIds = new ArrayList<>(
                notificationRecipientResolver.resolveActiveStudentRecipients(courseId));
        recipientIds.removeIf(id -> authorUserId != null && authorUserId.equals(id));

        NotificationDispatchPayload payload = new NotificationDispatchPayload();
        payload.setTenantId(course.getTenantId());
        payload.setCourseId(courseId);
        payload.setNotificationType(NotificationType.ANNOUNCEMENT_POSTED);
        payload.setMessage(notificationMessageFactory.announcementPosted(persisted.getTitle()));
        payload.setSubjectType(SubjectType.ANNOUNCEMENT);
        payload.setSubjectId(persisted.getId());
        payload.setEventKey("published");
        payload.setDeepLink("/courses/" + courseId + "/announcements/" + persisted.getId());
        payload.setRecipientIds(recipientIds);
        payload.setCreatedAt(notificationTimeSupport.nowUtc());
        notificationCommitHook.afterCommitDispatch(payload);
        markAnnouncementRead(persisted.getId(), authorUserId);
        return toDetail(persisted, true);
    }

    @Transactional
    public AnnouncementResponse update(Integer courseId, Integer announcementId, Integer actorUserId,
                                       UpdateAnnouncementRequest request) {
        requireCourseNotArchived(courseId);
        CourseAnnouncement existing = requireAnnouncementInCourse(courseId, announcementId);
        coursePermissionService.requireCanMutateAnnouncement(courseId, actorUserId, existing.getAuthorUserId());
        if (request == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Request body is required");
        }
        if (request.getTitle() == null && request.getBody() == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "title or body is required");
        }

        CourseAnnouncement patch = new CourseAnnouncement();
        patch.setId(announcementId);
        if (request.getTitle() != null) {
            String title = requireNonBlank(request.getTitle(), "title");
            if (title.length() > TITLE_MAX) {
                throw new ApiException(ErrorType.BAD_REQUEST, "title must be at most " + TITLE_MAX + " characters");
            }
            patch.setTitle(title);
        }
        if (request.getBody() != null) {
            patch.setBodyHtml(requireNonBlank(request.getBody(), "body"));
        }
        patch.setEditedAt(LocalDateTime.now());
        courseAnnouncementMapper.updateById(patch);

        CourseAnnouncement updated = requireAnnouncementInCourse(courseId, announcementId);
        boolean read = courseAnnouncementReadMapper.selectByAnnouncementAndUser(announcementId, actorUserId) != null;
        return toDetail(updated, read);
    }

    @Transactional
    public void delete(Integer courseId, Integer announcementId, Integer actorUserId, Boolean confirm) {
        requireCourseNotArchived(courseId);
        if (!Boolean.TRUE.equals(confirm)) {
            throw new ApiException(ErrorType.ANNOUNCEMENT_DELETE_CONFIRM_REQUIRED);
        }
        CourseAnnouncement existing = requireAnnouncementInCourse(courseId, announcementId);
        coursePermissionService.requireCanMutateAnnouncement(courseId, actorUserId, existing.getAuthorUserId());
        courseAnnouncementMapper.deleteById(announcementId);
    }

    public List<RecentAnnouncementResponse> listRecentForUser(Integer userId, Integer limit) {
        int lim = normalizeLimit(limit, RECENT_DEFAULT, RECENT_MAX);
        List<AnnouncementSummaryResponse> rows = courseAnnouncementMapper.selectRecentForUser(userId, lim);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<RecentAnnouncementResponse> result = new ArrayList<>(rows.size());
        for (AnnouncementSummaryResponse row : rows) {
            RecentAnnouncementResponse item = new RecentAnnouncementResponse();
            item.setCourseId(row.getCourseId());
            item.setId(row.getId());
            item.setCourseCode(row.getCourseCode());
            item.setTitle(row.getTitle());
            item.setPostedAt(row.getPostedAt());
            item.setUnread(!Boolean.TRUE.equals(row.getRead()));
            result.add(item);
        }
        return result;
    }

    private void markAnnouncementRead(Integer announcementId, Integer userId) {
        CourseAnnouncementRead read = new CourseAnnouncementRead();
        read.setAnnouncementId(announcementId);
        read.setUserId(userId);
        read.setReadAt(LocalDateTime.now());
        courseAnnouncementReadMapper.insertIgnore(read);
        try {
            notificationService.markSubjectRead(userId, SubjectType.ANNOUNCEMENT, announcementId);
        } catch (RuntimeException e) {
            log.warn("Could not sync announcement notification read state. announcementId={} userId={}",
                    announcementId, userId, e);
        }
    }

    private Course requireCourseNotArchived(Integer courseId) {
        Course course = requireCourse(courseId);
        if (STATE_ARCHIVED.equals(course.getState())) {
            throw new ApiException(ErrorType.COURSE_ARCHIVED);
        }
        return course;
    }

    private Course requireCourse(Integer courseId) {
        if (courseId == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Course id is required");
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ApiException(ErrorType.COURSE_NOT_FOUND);
        }
        return course;
    }

    private CourseAnnouncement requireAnnouncementInCourse(Integer courseId, Integer announcementId) {
        requireCourse(courseId);
        if (announcementId == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Announcement id is required");
        }
        CourseAnnouncement announcement = courseAnnouncementMapper.selectById(announcementId);
        if (announcement == null || !courseId.equals(announcement.getCourseId())) {
            throw new ApiException(ErrorType.ANNOUNCEMENT_GONE, "Content no longer available");
        }
        return announcement;
    }

    private String resolveAuthorName(Integer userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return "User " + userId;
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName().trim();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername().trim();
        }
        return "User " + userId;
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorType.PARAM_MISSING, field + " is required");
        }
        return value.trim();
    }

    private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
        if (limit == null || limit < 1) {
            return defaultLimit;
        }
        return Math.min(limit, maxLimit);
    }

    private AnnouncementResponse toDetail(CourseAnnouncement announcement, boolean read) {
        AnnouncementResponse response = new AnnouncementResponse();
        response.setId(announcement.getId());
        response.setCourseId(announcement.getCourseId());
        response.setTitle(announcement.getTitle());
        response.setBody(announcement.getBodyHtml());
        response.setAuthorUserId(announcement.getAuthorUserId());
        response.setAuthorName(announcement.getAuthorName());
        response.setPostedAt(announcement.getPostedAt());
        response.setEditedAt(announcement.getEditedAt());
        response.setRead(read);
        return response;
    }
}
