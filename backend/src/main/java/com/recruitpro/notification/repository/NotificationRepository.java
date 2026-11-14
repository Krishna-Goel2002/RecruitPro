package com.recruitpro.notification.repository;

import com.recruitpro.notification.entity.Notification;
import com.recruitpro.notification.entity.NotificationChannel;
import com.recruitpro.notification.entity.NotificationStatus;
import com.recruitpro.notification.entity.NotificationTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findAllByRecipientUserIdAndChannel(Long recipientUserId, NotificationChannel channel, Pageable pageable);

    long countByRecipientUserIdAndChannelAndReadAtIsNull(Long recipientUserId, NotificationChannel channel);

    Optional<Notification> findByIdAndRecipientUserIdAndChannel(Long id, Long recipientUserId, NotificationChannel channel);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now, n.updatedAt = :now "
            + "where n.recipientUserId = :userId and n.channel = :channel and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("channel") NotificationChannel channel, @Param("now") LocalDateTime now);

    List<Notification> findAllByChannelAndStatusOrderByCreatedAtAscIdAsc(NotificationChannel channel, NotificationStatus status,
                                                                         Pageable pageable);

    List<Notification> findAllByRecipientEmailAndTemplateOrderByIdAsc(String recipientEmail, NotificationTemplate template);

    /** Every notification (email outbox and inbox) of a user, oldest first (candidate data export). */
    List<Notification> findAllByRecipientUserIdOrderByIdAsc(Long recipientUserId);

    /** Account deletion: removes the user's inbox and outbox rows, also those addressed only by email. */
    @Modifying(flushAutomatically = true)
    @Query("delete from Notification n where n.recipientUserId = :userId or n.recipientEmail = :email")
    int deleteAllForRecipient(@Param("userId") Long userId, @Param("email") String email);
}
