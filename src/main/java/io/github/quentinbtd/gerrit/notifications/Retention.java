package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

@Singleton
public final class Retention implements LifecycleListener {
  private final NotificationStore store;
  private final Settings settings;
  private ScheduledExecutorService executor;

  @Inject
  Retention(NotificationStore store, Settings settings) {
    this.store = store;
    this.settings = settings;
  }

  @Override
  public void start() {
    executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
          var t = new Thread(r, "notification-center-retention");
          t.setDaemon(true);
          return t;
        });
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            store.prune(
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.retentionDays));
          } catch (Exception e) {
            Logger.getLogger(Retention.class.getName())
                .warning("notification-center retention failed; inspect storage health");
          }
        },
        settings.cleanupSeconds,
        settings.cleanupSeconds,
        TimeUnit.SECONDS);
  }

  @Override
  public void stop() {
    if (executor != null) executor.shutdownNow();
    try {
      store.close();
    } catch (Exception e) {
      Logger.getLogger(Retention.class.getName())
          .warning("notification-center storage close failed");
    }
  }
}
