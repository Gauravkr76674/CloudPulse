package com.cloudops.agent.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.cloudops.agent.entity.Notification;
import com.cloudops.agent.entity.User;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Everything this user can see: their own notifications plus broadcasts. */
    @Query("""
            select n from Notification n
            where n.broadcast = true or n.recipient = :user
            order by n.createdAt desc
            """)
    List<Notification> findVisibleTo(@Param("user") User user, Pageable pageable);

    @Query("""
            select n from Notification n
            where (n.broadcast = true or n.recipient = :user) and n.read = false
            order by n.createdAt desc
            """)
    List<Notification> findUnreadFor(@Param("user") User user, Pageable pageable);

    @Query("""
            select count(n) from Notification n
            where (n.broadcast = true or n.recipient = :user) and n.read = false
            """)
    long countUnreadFor(@Param("user") User user);

    @Modifying
    @Query("""
            update Notification n set n.read = true, n.readAt = :now
            where n.read = false and (n.broadcast = true or n.recipient = :user)
            """)
    int markAllReadFor(@Param("user") User user, @Param("now") Instant now);
}
