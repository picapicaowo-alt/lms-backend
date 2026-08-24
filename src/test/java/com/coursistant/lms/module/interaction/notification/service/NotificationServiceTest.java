package com.coursistant.lms.module.interaction.notification.service;

import com.coursistant.lms.module.interaction.notification.entity.UserNotification;
import com.coursistant.lms.module.interaction.notification.enums.SubjectType;
import com.coursistant.lms.module.interaction.notification.repository.UserNotificationMapper;
import com.coursistant.lms.module.user.account.entity.User;
import com.coursistant.lms.module.user.account.repository.UserMapper;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private UserNotificationMapper userNotificationMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private NotificationTimeSupport notificationTimeSupport;

    @Mock
    private NotificationWriteService notificationWriteService;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    void markRead_crossUser_throwsNotFound() {
        User user = new User();
        user.setId(10);
        user.setTenantId(1);
        when(userMapper.selectById(10)).thenReturn(user);
        when(userNotificationMapper.selectByIdForRecipient(99, 1, 10)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class,
                () -> notificationService.markRead(10, 99));
        assertEquals(ErrorType.NOT_FOUND, ex.getErrorType());
        verify(userNotificationMapper, never()).markRead(any(), any(), any(), any());
    }

    @Test
    void markRead_ownUnread_marksRead() {
        User user = new User();
        user.setId(10);
        user.setTenantId(1);
        when(userMapper.selectById(10)).thenReturn(user);

        UserNotification existing = new UserNotification();
        existing.setId(99);
        existing.setRecipientUserId(10);
        existing.setTenantId(1);
        existing.setReadAt(null);
        when(userNotificationMapper.selectByIdForRecipient(99, 1, 10)).thenReturn(existing);
        when(notificationTimeSupport.nowUtc()).thenReturn(java.time.LocalDateTime.of(2026, 7, 1, 12, 0, 0));

        notificationService.markRead(10, 99);

        verify(userNotificationMapper).markRead(eq(99), eq(1), eq(10), any());
    }

    @Test
    void list_usesTenantScopedPagination() {
        User user = new User();
        user.setId(10);
        user.setTenantId(7);
        when(userMapper.selectById(10)).thenReturn(user);
        when(userNotificationMapper.countByRecipient(7, 10)).thenReturn(2L);
        when(userNotificationMapper.selectPage(7, 10, 0, 20)).thenReturn(List.of());

        var page = notificationService.list(10, 1, 20);
        assertEquals(2L, page.getTotal());
        assertEquals(1, page.getPage());
        assertEquals(20, page.getSize());
    }

    @Test
    void unreadCount_scopedByTenant() {
        User user = new User();
        user.setId(10);
        user.setTenantId(7);
        when(userMapper.selectById(10)).thenReturn(user);
        when(userNotificationMapper.countUnread(7, 10)).thenReturn(4L);

        assertEquals(4L, notificationService.unreadCount(10).getUnreadCount());
    }

    @Test
    void markSubjectRead_updatesMatchingRows() {
        User user = new User();
        user.setId(10);
        user.setTenantId(7);
        when(userMapper.selectById(10)).thenReturn(user);
        when(notificationTimeSupport.nowUtc()).thenReturn(java.time.LocalDateTime.of(2026, 7, 1, 12, 0, 0));

        notificationService.markSubjectRead(10, SubjectType.ANNOUNCEMENT, 99);

        verify(notificationWriteService).markReadBySubject(
                eq(7), eq(10), eq("ANNOUNCEMENT"), eq(99), any());
    }
}
