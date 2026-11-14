package io.github.quentinbtd.gerrit.notifications;

import static org.junit.Assert.*;

import java.nio.file.Files;
import org.junit.Test;

public class StoreTest {
  @Test
  public void upgradesVersionOneWithoutDroppingInbox() throws Exception {
    String url = "jdbc:h2:" + Files.createTempDirectory("notifications-v1").resolve("inbox");
    try (var store = new H2Store(url)) {
      store.insert(7, "preserved", "COMMENT", 8, "p", 12, "/c/p/+/12", 100);
    }
    // Recreate the exact version-1 boundary: the schema-2 table/index and ledger entry absent.
    try (var c = java.sql.DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.execute("DROP TABLE comment_events");
      s.execute("DELETE FROM schema_version WHERE version=2");
    }
    try (var store = new H2Store(url)) {
      assertEquals(1, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      assertTrue(store.claimComment("p", 12, "comment", "event", 100));
    }
    try (var c = java.sql.DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement();
        var r = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
      assertTrue(r.next());
      assertEquals(2, r.getInt(1));
    }
  }

  @Test
  public void inlineIdentityBindsToFirstEventAndSurvivesRepeatedCallbacks() throws Exception {
    try (var store = new H2Store("jdbc:h2:mem:inline-identities")) {
      assertTrue(store.claimComment("p", 12, "comment-uuid", "event-a", 100));
      assertTrue(store.claimComment("p", 12, "comment-uuid", "event-a", 100));
      assertFalse(store.claimComment("p", 12, "comment-uuid", "event-b", 101));
      assertTrue(store.claimComment("p", 12, "another-uuid", "event-b", 101));
    }
  }

  @Test
  public void scopesMutationsPaginatesFiltersAndRetains() throws Exception {
    try (H2Store store = new H2Store("jdbc:h2:mem:state")) {
      for (int i = 1; i <= 4; i++)
        store.insert(7, "e" + i, i == 4 ? "MERGED" : "COMMENT", 8, "p", 12, "/c/p/+/12", i * 100);
      var first = store.page(7, "all", null, Long.MAX_VALUE, 2);
      assertEquals(2, first.size());
      long id = first.get(0).id();
      assertFalse(store.update(8, id, "read", 500));
      assertTrue(store.update(7, id, "read", 500));
      assertEquals(3, store.page(7, "unread", null, Long.MAX_VALUE, 100).size());
      assertTrue(store.update(7, id, "unread", 500));
      assertEquals(4, store.page(7, "unread", null, Long.MAX_VALUE, 100).size());
      assertTrue(store.update(7, id, "archive", 500));
      assertEquals(1, store.page(7, "archived", "MERGED", Long.MAX_VALUE, 100).size());
      assertEquals(2, store.page(7, "all", null, first.get(1).id(), 2).size());
      assertEquals(2, store.prune(300));
      assertEquals(1, store.page(7, "all", null, Long.MAX_VALUE, 100).size());
    }
  }

  @Test
  public void rejectsFutureSchema() throws Exception {
    String url = "jdbc:h2:mem:future;DB_CLOSE_DELAY=-1";
    try (var c = java.sql.DriverManager.getConnection(url, "sa", "");
        var s = c.createStatement()) {
      s.execute("CREATE TABLE schema_version(version INT PRIMARY KEY)");
      s.execute("INSERT INTO schema_version VALUES(999)");
    }
    try {
      new H2Store(url);
      fail("future schema must fail closed");
    } catch (java.sql.SQLException expected) {
      assertTrue(expected.getMessage().contains("schema"));
    }
  }

  @Test
  public void persistsAndDeduplicatesAcrossRestart() throws Exception {
    String url = "jdbc:h2:" + Files.createTempDirectory("notifications-test").resolve("inbox");
    try (H2Store store = new H2Store(url)) {
      store.insert(7, "event-1", "COMMENT", 8, "project", 12, "/c/project/+/12", 100);
      store.insert(7, "event-1", "COMMENT", 8, "project", 12, "/c/project/+/12", 100);
      assertEquals(1, store.page(7, "all", null, Long.MAX_VALUE, 50).size());
      assertTrue(store.page(8, "all", null, Long.MAX_VALUE, 50).isEmpty());
    }
    try (H2Store store = new H2Store(url)) {
      assertEquals(1, store.page(7, "unread", null, Long.MAX_VALUE, 50).size());
    }
  }
}
