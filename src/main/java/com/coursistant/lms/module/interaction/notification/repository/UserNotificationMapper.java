package com.coursistant.lms.module.interaction.notification.repository;

import com.coursistant.lms.module.interaction.notification.dto.NotificationResponse;
import com.coursistant.lms.module.interaction.notification.entity.UserNotification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserNotificationMapper {

    int insertChunk(@Param("rows") List<UserNotification> rows);

    List<NotificationResponse> selectPage(@Param("tenantId") Integer tenantId,
                                          @Param("recipientUserId") Integer recipientUserId,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    long countByRecipient(@Param("tenantId") Integer tenantId,
                          @Param("recipientUserId") Integer recipientUserId);

    long countUnread(@Param("tenantId") Integer tenantId,
                     @Param("recipientUserId") Integer recipientUserId);

    UserNotification selectByIdForRecipient(@Param("id") Integer id,
                                            @Param("tenantId") Integer tenantId,
                                            @Param("recipientUserId") Integer recipientUserId);

    int markRead(@Param("id") Integer id,
                 @Param("tenantId") Integer tenantId,
                 @Param("recipientUserId") Integer recipientUserId,
                 @Param("readAt") LocalDateTime readAt);

    int markAllRead(@Param("tenantId") Integer tenantId,
                    @Param("recipientUserId") Integer recipientUserId,
                    @Param("readAt") LocalDateTime readAt);

    int markReadBySubject(@Param("tenantId") Integer tenantId,
                          @Param("recipientUserId") Integer recipientUserId,
                          @Param("subjectType") String subjectType,
                          @Param("subjectId") Integer subjectId,
                          @Param("readAt") LocalDateTime readAt);
}
