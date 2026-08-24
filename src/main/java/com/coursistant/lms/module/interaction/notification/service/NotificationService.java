package com.coursistant.lms.module.interaction.notification.service;

import com.coursistant.lms.module.interaction.notification.dto.NotificationPageResponse;
import com.coursistant.lms.module.interaction.notification.dto.NotificationResponse;
import com.coursistant.lms.module.interaction.notification.dto.UnreadCountResponse;
import com.coursistant.lms.module.interaction.notification.entity.UserNotification;
import com.coursistant.lms.module.interaction.notification.enums.SubjectType;
import com.coursistant.lms.module.interaction.notification.repository.UserNotificationMapper;
import com.coursistant.lms.module.user.account.entity.User;
import com.coursistant.lms.module.user.account.repository.UserMapper;
import com.coursistant.lms.shared.api.ApiException;
import com.coursistant.lms.shared.api.ErrorType;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @Resource
    private UserNotificationMapper userNotificationMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private NotificationTimeSupport notificationTimeSupport;

    @Resource
    private NotificationWriteService notificationWriteService;

    public NotificationPageResponse list(Integer userId, Integer page, Integer size) {
        Integer tenantId = requireTenantId(userId);
        int pageNum = normalizePage(page);
        int pageSize = normalizeSize(size);
        int offset = (pageNum - 1) * pageSize;

        long total = userNotificationMapper.countByRecipient(tenantId, userId);
        List<NotificationResponse> items = userNotificationMapper.selectPage(tenantId, userId, offset, pageSize);

        NotificationPageResponse response = new NotificationPageResponse();
        response.setItems(items);
        response.setPage(pageNum);
        response.setSize(pageSize);
        response.setTotal(total);
        return response;
    }

    public UnreadCountResponse unreadCount(Integer userId) {
        Integer tenantId = requireTenantId(userId);
        long count = userNotificationMapper.countUnread(tenantId, userId);
        return new UnreadCountResponse(count);
    }

    public void markRead(Integer userId, Integer notificationId) {
        if (notificationId == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "Notification id is required");
        }
        Integer tenantId = requireTenantId(userId);
        UserNotification existing = userNotificationMapper.selectByIdForRecipient(notificationId, tenantId, userId);
        if (existing == null) {
            throw new ApiException(ErrorType.NOT_FOUND);
        }
        if (existing.getReadAt() != null) {
            return;
        }
        userNotificationMapper.markRead(notificationId, tenantId, userId, notificationTimeSupport.nowUtc());
    }

    public UnreadCountResponse markAllRead(Integer userId) {
        Integer tenantId = requireTenantId(userId);
        userNotificationMapper.markAllRead(tenantId, userId, notificationTimeSupport.nowUtc());
        return new UnreadCountResponse(0);
    }

    public void markSubjectRead(Integer userId, SubjectType subjectType, Integer subjectId) {
        if (userId == null || subjectType == null || subjectId == null) {
            return;
        }
        Integer tenantId = requireTenantId(userId);
        notificationWriteService.markReadBySubject(
                tenantId, userId, subjectType.name(), subjectId, notificationTimeSupport.nowUtc());
    }

    private Integer requireTenantId(Integer userId) {
        if (userId == null) {
            throw new ApiException(ErrorType.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(ErrorType.USER_NOT_FOUND);
        }
        if (user.getTenantId() == null) {
            throw new ApiException(ErrorType.BAD_REQUEST, "User tenant is required");
        }
        return user.getTenantId();
    }

    private int normalizePage(Integer page) {
        if (page == null || page < 1) {
            return DEFAULT_PAGE;
        }
        return page;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
