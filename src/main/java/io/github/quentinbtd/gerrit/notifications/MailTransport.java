package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.Address;
import java.io.IOException;
import java.util.Collection;

/** Delivers one complete RFC 5322 message. Replaced by an in-memory fake in acceptance tests. */
public interface MailTransport {
  boolean configured();

  void send(Address from, Collection<Address> to, String message) throws IOException;
}
