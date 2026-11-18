package io.github.quentinbtd.gerrit.notifications;

import java.util.Set;

public record DeliveryEvent(
    String type,
    String key,
    int actor,
    int owner,
    String project,
    int change,
    String url,
    long when,
    Set<Integer> reviewers,
    Set<Integer> added,
    Set<Integer> mentions,
    Set<Integer> watchers) {}
