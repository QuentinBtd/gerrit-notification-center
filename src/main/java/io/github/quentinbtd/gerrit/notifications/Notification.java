package io.github.quentinbtd.gerrit.notifications;

public record Notification(
    long id,
    int accountId,
    String type,
    int actor,
    String project,
    int change,
    String url,
    long createdAt,
    Long readAt,
    Long archivedAt,
    Long savedAt) {}
