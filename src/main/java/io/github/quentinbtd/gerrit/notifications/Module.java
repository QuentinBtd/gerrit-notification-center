package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.extensions.client.MenuItem;
import com.google.gerrit.extensions.events.*;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.*;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.account.AccountResource;
import com.google.inject.*;
import java.util.List;

public final class Module extends AbstractModule {
  @Override
  protected void configure() {
    DynamicSet.bind(binder(), CommentAddedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ReviewerAddedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), RevisionCreatedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ChangeMergedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), ChangeAbandonedListener.class).to(GerritListeners.class);
    DynamicSet.bind(binder(), WebUiPlugin.class)
        .toInstance(new JavaScriptPlugin("notification-center.js"));
    DynamicSet.bind(binder(), TopMenu.class)
        .toInstance(
            () ->
                List.of(
                    new TopMenu.MenuEntry(
                        "Notifications",
                        List.of(new MenuItem("Inbox", "/x/notification-center/notifications")))));
    install(
        new LifecycleModule() {
          @Override
          protected void configure() {
            listener().to(Retention.class);
          }
        });
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(AccountResource.ACCOUNT_KIND, "notifications").to(GetNotifications.class);
            post(AccountResource.ACCOUNT_KIND, "notifications").to(PostNotifications.class);
          }
        });
  }

  @Provides
  @Singleton
  NotificationStore store(Settings settings) throws Exception {
    Class.forName("org.h2.Driver");
    return new H2Store(settings.jdbcUrl);
  }

  @Provides
  @Singleton
  EventRouter router(NotificationStore store, Settings settings) {
    return new EventRouter(store, settings.events);
  }

  @Provides
  @Singleton
  NotificationService service(NotificationStore store, GerritVisibility visibility) {
    return new NotificationService(store, visibility);
  }
}
