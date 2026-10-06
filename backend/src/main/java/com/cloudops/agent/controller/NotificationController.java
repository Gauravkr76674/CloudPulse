package com.cloudops.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudops.agent.dto.NotificationDto;
import com.cloudops.agent.security.CurrentUser;
import com.cloudops.agent.service.NotificationService;
import com.cloudops.agent.util.DtoMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * In-app notifications for the current user. Every endpoint is scoped to
 * {@link CurrentUser#username()}, so one user can never read or clear another's feed.
 */
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "Notifications", description = "Alerts for the signed-in user")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationDto> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                     @RequestParam(required = false) Integer limit) {
        return DtoMapper.toNotificationDtos(
                notificationService.listFor(CurrentUser.username(), unreadOnly, limit));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("unread", notificationService.countUnread(CurrentUser.username()));
    }

    @PostMapping("/{id}/read")
    public NotificationDto markRead(@PathVariable Long id) {
        return DtoMapper.toNotificationDto(
                notificationService.markRead(id, CurrentUser.username()));
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark every visible notification as read")
    public Map<String, Integer> markAllRead() {
        return Map.of("updated", notificationService.markAllRead(CurrentUser.username()));
    }
}
