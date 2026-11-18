package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.nio.file.*;
import java.util.*;

@Singleton
public final class Settings {
  public final boolean enabled, email;
  public final String jdbcUrl;
  public final int retentionDays, cleanupSeconds, pollSeconds;
  public final Set<String> events;

  @Inject
  Settings(PluginConfigFactory factory, @PluginName String name, SitePaths site) throws Exception {
    var c = factory.getFromGerritConfig(name);
    enabled = c.getBoolean("enabled", true);
    email = c.getBoolean("email", true);
    retentionDays = c.getInt("retentionDays", 90);
    cleanupSeconds = c.getInt("cleanupSeconds", 3600);
    pollSeconds = c.getInt("pollSeconds", 45);
    if (retentionDays < 1 || cleanupSeconds < 60 || pollSeconds < 30 || pollSeconds > 3600)
      throw new IllegalArgumentException("invalid notification-center intervals");
    Path path =
        site.resolve(c.getString("storagePath", "data/notification-center/inbox"))
            .toAbsolutePath()
            .normalize();
    if (path.toString().contains(";") || path.toString().contains("\n"))
      throw new IllegalArgumentException("invalid storage path");
    Files.createDirectories(path.getParent());
    jdbcUrl = "jdbc:h2:" + path;
    Set<String> types = new HashSet<>();
    for (String type : EventRouter.TYPES) if (c.getBoolean("event" + type, true)) types.add(type);
    events = enabled ? Set.copyOf(types) : Set.of();
  }
}
