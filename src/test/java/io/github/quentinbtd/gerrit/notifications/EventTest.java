package io.github.quentinbtd.gerrit.notifications;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class EventTest {
  @Test
  public void mentionsOverrideCommentsExcludeActorAndDeduplicate() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:events")) {
      var router =
          new EventRouter(
              store,
              Set.of("COMMENT", "MENTION", "REVIEWER_ADDED", "PATCHSET", "MERGED", "ABANDONED"),
              (a, t, ev) -> {});
      var e =
          new DeliveryEvent(
              "COMMENT",
              "stable-action",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              100,
              Set.of(8, 9),
              Set.of(),
              Set.of(9, 10),
              Set.of());
      router.receive(e);
      router.receive(e);
      assertEquals("COMMENT", store.page(7, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertEquals("MENTION", store.page(9, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertEquals(1, store.page(9, "all", null, Long.MAX_VALUE, 50).size());
      assertEquals("MENTION", store.page(10, "all", null, Long.MAX_VALUE, 50).get(0).type());
      assertTrue(store.page(8, "all", null, Long.MAX_VALUE, 50).isEmpty());
      router.receive(
          new DeliveryEvent(
              "REVIEWER_ADDED",
              "reviewer",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              101,
              Set.of(9),
              Set.of(10),
              Set.of(),
              Set.of()));
      assertEquals(2, store.page(10, "all", null, Long.MAX_VALUE, 50).size());
      assertEquals(1, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      for (String type : List.of("PATCHSET", "MERGED", "ABANDONED"))
        router.receive(
            new DeliveryEvent(
                type,
                type,
                8,
                7,
                "p",
                12,
                "/c/p/+/12",
                102,
                Set.of(9),
                Set.of(),
                Set.of(),
                Set.of()));
      assertEquals(4, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      router.receive(null);
    }
  }

  @Test
  public void disabledAndIncompleteEventsAreIgnored() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:disabled")) {
      var router = new EventRouter(store, Set.of(), (a, t, ev) -> {});
      router.receive(
          new DeliveryEvent(
              "MERGED",
              "x",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              100,
              Set.of(),
              Set.of(),
              Set.of(),
              Set.of()));
      assertTrue(store.page(7, "all", null, Long.MAX_VALUE, 50).isEmpty());
    }
  }

  @Test
  public void watchersAddRecipientsWithoutOverridingAndReportOnlyNewRows() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:watchers")) {
      List<String> delivered = new ArrayList<>();
      var router =
          new EventRouter(
              store,
              Set.of("COMMENT", "MENTION", "NEW_CHANGE"),
              (a, t, ev) -> delivered.add(a + ":" + t));
      var e =
          new DeliveryEvent(
              "COMMENT",
              "k",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              100,
              Set.of(),
              Set.of(),
              Set.of(9),
              Set.of(8, 9, 20));
      router.receive(e);
      router.receive(e);
      assertEquals(
          List.of("20:COMMENT"), delivered.stream().filter(d -> d.startsWith("20")).toList());
      assertEquals(Set.of("7:COMMENT", "9:MENTION", "20:COMMENT"), new HashSet<>(delivered));
      assertTrue(store.page(8, "all", null, Long.MAX_VALUE, 50).isEmpty());
    }
  }

  @Test
  public void failingChannelDoesNotStopInboxDeliveryToOtherRecipients() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:failing-channel")) {
      var router =
          new EventRouter(
              store,
              Set.of("MERGED"),
              (a, t, ev) -> {
                throw new RejectedExecutionException("queue full");
              });
      router.receive(
          new DeliveryEvent(
              "MERGED",
              "m",
              8,
              7,
              "p",
              12,
              "/c/p/+/12",
              100,
              Set.of(9, 10),
              Set.of(),
              Set.of(),
              Set.of(11)));
      for (int account : List.of(7, 9, 10, 11))
        assertEquals(1, store.page(account, "all", null, Long.MAX_VALUE, 50).size());
    }
  }

  @Test
  public void messageIdsDifferPerEventAndRecipientAtSameTimestamp() {
    var a =
        new DeliveryEvent(
            "NEW_CHANGE",
            "p:12:8:T:1",
            8,
            7,
            "p",
            12,
            "/c/p/+/12",
            100,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of());
    var b =
        new DeliveryEvent(
            "NEW_CHANGE",
            "p:13:8:T:1",
            8,
            7,
            "p",
            13,
            "/c/p/+/13",
            100,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of());
    assertNotEquals(Mailer.messageId(9, a), Mailer.messageId(9, b));
    assertNotEquals(Mailer.messageId(9, a), Mailer.messageId(10, a));
    assertEquals(Mailer.messageId(9, a), Mailer.messageId(9, a));
  }

  @Test
  public void emailQueueIsBoundedAndOverflowNeverThrows() throws Exception {
    var overflow = new AtomicInteger();
    var executor = Mailer.queue(1, overflow::incrementAndGet);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    try {
      executor.execute(
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      started.await(); // worker busy (simulated SMTP timeout)
      for (int i = 0; i < 5; i++) executor.execute(() -> {});
      assertEquals(1, executor.getQueue().size());
      assertEquals(4, overflow.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  public void resolvesOnlyExplicitUnambiguousMentions() throws Exception {
    var resolver =
        new RecipientResolver(email -> email.equals("a@example.org") ? Set.of(7) : Set.of(8, 9));
    assertEquals(
        Set.of(7, 12),
        resolver.mentions(
            "Hi @a@example.org @ambiguous@example.org <GERRIT_ACCOUNT_12>"
                + " <GERRIT_ACCOUNT_99999999999999999999>"));
    assertTrue(resolver.mentions(null).isEmpty());
    assertTrue(resolver.mentions("ordinary a@example.org @unknown").isEmpty());
  }
}
