package com.coursistant.lms.module.course.announcement.service;

import com.coursistant.lms.module.course.announcement.dto.AnnouncementResponse;
import com.coursistant.lms.module.course.announcement.dto.AnnouncementSummaryResponse;
import com.coursistant.lms.module.course.announcement.dto.CreateAnnouncementRequest;
import com.coursistant.lms.module.course.announcement.dto.RecentAnnouncementResponse;
import com.coursistant.lms.module.course.announcement.entity.CourseAnnouncement;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CourseAnnouncementServiceTest {

    @Mock
    private CourseAnnouncementMapper courseAnnouncementMapper;

    @Mock
    private CourseAnnouncementReadMapper courseAnnouncementReadMapper;

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CoursePermissionService coursePermissionService;

    @Mock
    private NotificationRecipientResolver notificationRecipientResolver;

    @Mock
    private NotificationMessageFactory notificationMessageFactory;

    @Mock
    private NotificationCommitHook notificationCommitHook;

    @Mock
    private NotificationTimeSupport notificationTimeSupport;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private CourseAnnouncementService courseAnnouncementService;

    @Test
    void create_dispatchesStudentRecipientsExcludingAuthor() {
        Course course = new Course();
        course.setId(17);
        course.setTenantId(3);
        course.setState("Active");
        when(courseMapper.selectById(17)).thenReturn(course);
        doNothing().when(coursePermissionService).requireCanPostAnnouncements(17, 10);

        User user = new User();
        user.setId(10);
        user.setName("Teacher");
        when(userMapper.selectById(10)).thenReturn(user);

        when(courseAnnouncementMapper.insert(any(CourseAnnouncement.class))).thenAnswer(invocation -> {
            CourseAnnouncement a = invocation.getArgument(0);
            a.setId(99);
            return 1;
        });
        when(courseAnnouncementMapper.selectById(99)).thenAnswer(invocation -> {
            CourseAnnouncement a = new CourseAnnouncement();
            a.setId(99);
            a.setCourseId(17);
            a.setTitle("Hello");
            a.setBodyHtml("Body");
            a.setAuthorUserId(10);
            a.setAuthorName("Teacher");
            return a;
        });
        when(notificationRecipientResolver.resolveActiveStudentRecipients(17))
                .thenReturn(new ArrayList<>(List.of(10, 385, 50)));
        when(notificationMessageFactory.announcementPosted("Hello")).thenReturn("New announcement: Hello");
        when(notificationTimeSupport.nowUtc()).thenReturn(java.time.LocalDateTime.of(2026, 7, 1, 12, 0, 0));
        doNothing().when(notificationCommitHook).afterCommitDispatch(any());

        CreateAnnouncementRequest request = new CreateAnnouncementRequest();
        request.setTitle("Hello");
        request.setBody("Body");

        AnnouncementResponse response = courseAnnouncementService.create(17, 10, request);

        assertNotNull(response);
        assertEquals(99, response.getId());
        assertTrue(response.getRead());
        verify(courseAnnouncementReadMapper).insertIgnore(any());
        verify(notificationService).markSubjectRead(10, SubjectType.ANNOUNCEMENT, 99);

        ArgumentCaptor<NotificationDispatchPayload> captor =
                ArgumentCaptor.forClass(NotificationDispatchPayload.class);
        verify(notificationCommitHook).afterCommitDispatch(captor.capture());
        NotificationDispatchPayload payload = captor.getValue();
        assertEquals(3, payload.getTenantId());
        assertEquals(17, payload.getCourseId());
        assertEquals(NotificationType.ANNOUNCEMENT_POSTED, payload.getNotificationType());
        assertEquals(SubjectType.ANNOUNCEMENT, payload.getSubjectType());
        assertEquals(99, payload.getSubjectId());
        assertEquals("published", payload.getEventKey());
        assertEquals("/courses/17/announcements/99", payload.getDeepLink());
        assertEquals(List.of(385, 50), payload.getRecipientIds());
        assertFalse(payload.getRecipientIds().contains(10));
    }

    @Test
    void create_whenResolverReturnsEmpty_stillSucceedsWithoutFailingBusiness() {
        Course course = new Course();
        course.setId(17);
        course.setTenantId(3);
        course.setState("Active");
        when(courseMapper.selectById(17)).thenReturn(course);
        doNothing().when(coursePermissionService).requireCanPostAnnouncements(17, 10);

        User user = new User();
        user.setId(10);
        user.setName("Teacher");
        when(userMapper.selectById(10)).thenReturn(user);

        when(courseAnnouncementMapper.insert(any(CourseAnnouncement.class))).thenAnswer(invocation -> {
            CourseAnnouncement a = invocation.getArgument(0);
            a.setId(99);
            return 1;
        });
        when(courseAnnouncementMapper.selectById(99)).thenAnswer(invocation -> {
            CourseAnnouncement a = new CourseAnnouncement();
            a.setId(99);
            a.setCourseId(17);
            a.setTitle("Hello");
            a.setBodyHtml("Body");
            a.setAuthorUserId(10);
            a.setAuthorName("Teacher");
            return a;
        });
        when(notificationRecipientResolver.resolveActiveStudentRecipients(17))
                .thenReturn(List.of());
        when(notificationMessageFactory.announcementPosted("Hello")).thenReturn("New announcement: Hello");
        when(notificationTimeSupport.nowUtc()).thenReturn(java.time.LocalDateTime.of(2026, 7, 1, 12, 0, 0));
        doNothing().when(notificationCommitHook).afterCommitDispatch(any());

        CreateAnnouncementRequest request = new CreateAnnouncementRequest();
        request.setTitle("Hello");
        request.setBody("Body");

        AnnouncementResponse response = courseAnnouncementService.create(17, 10, request);
        assertNotNull(response);
        assertEquals(99, response.getId());
        assertTrue(response.getRead());
        verify(courseAnnouncementReadMapper).insertIgnore(any());
        verify(notificationService).markSubjectRead(10, SubjectType.ANNOUNCEMENT, 99);

        ArgumentCaptor<NotificationDispatchPayload> captor =
                ArgumentCaptor.forClass(NotificationDispatchPayload.class);
        verify(notificationCommitHook).afterCommitDispatch(captor.capture());
        assertEquals(List.of(), captor.getValue().getRecipientIds());
    }

    @Test
    void delete_withoutConfirm_throwsConfirmRequired() {
        Course course = new Course();
        course.setId(17);
        course.setState("Active");
        when(courseMapper.selectById(17)).thenReturn(course);

        ApiException ex = assertThrows(ApiException.class,
                () -> courseAnnouncementService.delete(17, 1, 10, false));
        assertEquals(ErrorType.ANNOUNCEMENT_DELETE_CONFIRM_REQUIRED, ex.getErrorType());
        verify(courseAnnouncementMapper, never()).deleteById(any());
    }

    @Test
    void getById_missing_throwsAnnouncementGone() {
        Course course = new Course();
        course.setId(17);
        course.setState("Active");
        when(courseMapper.selectById(17)).thenReturn(course);
        when(courseAnnouncementMapper.selectById(404)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
                () -> courseAnnouncementService.getById(17, 404, 10));
        assertEquals(ErrorType.ANNOUNCEMENT_GONE, ex.getErrorType());
        assertEquals("Content no longer available", ex.getMessage());
    }

    @Test
    void getById_marksAnnouncementAndMatchingNotificationRead() {
        Course course = new Course();
        course.setId(17);
        course.setState("Active");
        when(courseMapper.selectById(17)).thenReturn(course);

        CourseAnnouncement announcement = new CourseAnnouncement();
        announcement.setId(99);
        announcement.setCourseId(17);
        announcement.setTitle("Hello");
        announcement.setBodyHtml("Body");
        announcement.setAuthorUserId(10);
        announcement.setAuthorName("Teacher");
        when(courseAnnouncementMapper.selectById(99)).thenReturn(announcement);

        AnnouncementResponse response = courseAnnouncementService.getById(17, 99, 385);

        assertTrue(response.getRead());
        verify(courseAnnouncementReadMapper).insertIgnore(any());
        verify(notificationService).markSubjectRead(385, SubjectType.ANNOUNCEMENT, 99);
    }

    @Test
    void listRecentForUser_mapsReadFlagToUnread() {
        AnnouncementSummaryResponse unread = new AnnouncementSummaryResponse();
        unread.setId(1);
        unread.setCourseId(17);
        unread.setCourseCode("CSCI-310");
        unread.setTitle("Unread post");
        unread.setRead(false);
        AnnouncementSummaryResponse read = new AnnouncementSummaryResponse();
        read.setId(2);
        read.setCourseId(17);
        read.setCourseCode("CSCI-310");
        read.setTitle("Own post");
        read.setRead(true);
        when(courseAnnouncementMapper.selectRecentForUser(10, 10)).thenReturn(List.of(unread, read));

        List<RecentAnnouncementResponse> result = courseAnnouncementService.listRecentForUser(10, null);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getUnread());
        assertFalse(result.get(1).getUnread());
    }
}
