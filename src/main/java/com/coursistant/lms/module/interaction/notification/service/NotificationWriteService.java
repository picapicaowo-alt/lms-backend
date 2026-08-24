package com.coursistant.lms.module.interaction.notification.service;

import com.coursistant.lms.module.interaction.notification.entity.UserNotification;
import com.coursistant.lms.module.interaction.notification.repository.UserNotificationMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Isolated write path for notifications. Each call runs in a fresh transaction.
 * Callers (Dispatcher) own chunking and must invoke this method via Spring proxy.
 */
@Service
public class NotificationWriteService {

    @Resource
    private UserNotificationMapper userNotificationMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int insertChunk(List<UserNotification> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return userNotificationMapper.insertChunk(rows);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markReadBySubject(Integer tenantId, Integer recipientUserId, String subjectType,
                                 Integer subjectId, LocalDateTime readAt) {
        if (tenantId == null || recipientUserId == null || subjectType == null || subjectId == null || readAt == null) {
            return 0;
        }
        return userNotificationMapper.markReadBySubject(tenantId, recipientUserId, subjectType, subjectId, readAt);
    }
}
