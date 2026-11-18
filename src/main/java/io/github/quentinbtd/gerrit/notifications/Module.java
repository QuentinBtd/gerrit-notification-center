package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.events.*;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.*;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class Module extends AbstractModule {
  @Override
  protected void configure() {
    DynamicSet.bind(binder(), CommentAddedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ReviewerAddedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), RevisionCreatedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ChangeMergedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ChangeAbandonedListener.class).to(GerritListeners.class);
    bind(MailTransport.class).to(SmtpTransport.class);
    DynamicSet.bind(binder(), WebUiPlugin.class)
        .toInstance(new JavaScriptPlugin("notification-center.js?v=" + frontendVersion()));
    install(
        new LifecycleModule() {
          @Override
          protected void configure() {
            listener().to(Retention.class);
            listener().to(Mailer.class);
          }
        });
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(AccountResource.ACCOUNT_KIND, "notifications").to(GetNotifications.class);
            post(AccountResource.ACCOUNT_KIND, "notifications").to(PostNotifications.class);
            get(AccountResource.ACCOUNT_KIND, "preferences").to(PreferencesRest.Get.class);
            put(AccountResource.ACCOUNT_KIND, "preferences").to(PreferencesRest.Put.class);
          }
        });
  }

  /**
   * Content hash of the bundled UI. Gerrit serves plugin files with the JAR entry time as
   * Last-Modified, which our reproducible build pins, so browsers would keep a stale script after
   * an upgrade. A new query string per UI build makes them fetch it (Gerrit ignores the query).
   */
  static String frontendVersion() {
    try (var in = Module.class.getResourceAsStream("/static/notification-center.js")) {
      return in == null
          ? "dev"
          : EventRouter.digest(new String(in.readAllBytes(), StandardCharsets.UTF_8))
              .substring(0, 12);
    } catch (IOException e) {
      return "dev";
    }
  }

  @Provides
  @Singleton
  NotificationStore store(Settings settings) throws Exception {
    Class.forName("org.h2.Driver");
    return new H2Store(settings.jdbcUrl);
  }

  @Provides
  @Singleton
  EventRouter router(NotificationStore store, Settings settings, Mailer mailer) {
    return new EventRouter(store, settings.events, mailer);
  }

  @Provides
  @Singleton
  NotificationService service(NotificationStore store, GerritVisibility visibility) {
    return new NotificationService(store, visibility);
  }
}
