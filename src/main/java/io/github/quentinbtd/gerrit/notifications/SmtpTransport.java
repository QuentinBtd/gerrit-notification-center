package io.github.quentinbtd.gerrit.notifications;

import com.google.gerrit.entities.Address;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.securestore.SecureStore;
import com.google.inject.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import org.apache.commons.net.smtp.AuthenticatingSMTPClient;
import org.apache.commons.net.smtp.SMTPReply;

/**
 * The plugin's own SMTP client (Apache Commons Net, shipped with Gerrit), configured in the plugin
 * section so Gerrit's own email can stay off. smtpPass is read from etc/secure.config.
 */
@Singleton
final class SmtpTransport implements MailTransport {
  private final String server, encryption, user, password;
  private final int port, timeoutMs;
  private final SSLContext ssl; // null: the JVM's default trust store

  @Inject
  SmtpTransport(Settings s, SecureStore secure, @PluginName String plugin) {
    this(
        s.smtpServer,
        s.smtpServerPort,
        s.smtpEncryption,
        s.smtpUser,
        secure.get("plugin", plugin, "smtpPass"),
        10_000);
  }

  SmtpTransport(
      String server, int port, String encryption, String user, String password, int timeoutMs) {
    this(server, port, encryption, user, password, timeoutMs, null);
  }

  SmtpTransport(
      String server,
      int port,
      String encryption,
      String user,
      String password,
      int timeoutMs,
      SSLContext ssl) {
    this.ssl = ssl;
    this.timeoutMs = timeoutMs;
    this.server = server;
    this.port = port;
    this.encryption = encryption;
    this.user = user;
    this.password = password;
  }

  @Override
  public boolean configured() {
    return server != null;
  }

  @Override
  public void send(Address from, Collection<Address> to, String message) throws IOException {
    SSLContext context;
    try {
      context = ssl != null ? ssl : SSLContext.getDefault();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException("no TLS support", e);
    }
    var client =
        new AuthenticatingSMTPClient(encryption.equals("ssl"), context) {
          @Override
          protected void _connectAction_() throws IOException {
            // Implicit SSL handshakes before the parent applies the default timeout: a silent
            // server would otherwise block the only mail worker forever.
            _socket_.setSoTimeout(timeoutMs);
            super._connectAction_();
          }
        };
    client.setEndpointCheckingEnabled(true); // the certificate must match smtpServer
    client.setConnectTimeout(timeoutMs);
    client.setDefaultTimeout(timeoutMs);
    try {
      client.connect(server, port);
      client.setSoTimeout(timeoutMs);
      expect(client, client.getReplyCode());
      String helo = helo();
      expect(client, client.ehlo(helo));
      if (encryption.equals("tls")) {
        if (!client.execTLS())
          throw new IOException("STARTTLS refused: " + client.getReplyString());
        expect(client, client.ehlo(helo));
      }
      if (user != null) {
        try {
          if (!client.auth(AuthenticatingSMTPClient.AUTH_METHOD.PLAIN, user, password))
            throw new IOException("authentication refused: " + client.getReplyString());
        } catch (java.security.GeneralSecurityException e) {
          throw new IOException("authentication failed", e);
        }
      }
      if (!client.setSender(from.email()))
        throw new IOException("sender refused: " + client.getReplyString());
      for (var rcpt : to)
        if (!client.addRecipient(rcpt.email()))
          throw new IOException("recipient refused: " + client.getReplyString());
      try (var data = client.sendMessageData()) {
        if (data == null) throw new IOException("DATA refused: " + client.getReplyString());
        data.write(message);
      }
      if (!client.completePendingCommand())
        throw new IOException("message refused: " + client.getReplyString());
      var unused = client.logout();
    } finally {
      if (client.isConnected()) client.disconnect();
    }
  }

  private static void expect(AuthenticatingSMTPClient client, int code) throws IOException {
    if (!SMTPReply.isPositiveCompletion(code))
      throw new IOException("SMTP server refused: " + client.getReplyString());
  }

  private static String helo() {
    try {
      return InetAddress.getLocalHost().getCanonicalHostName();
    } catch (IOException e) {
      return "localhost";
    }
  }
}
