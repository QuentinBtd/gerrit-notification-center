package io.github.quentinbtd.gerrit.notifications;

import static org.junit.Assert.*;

import com.google.gerrit.entities.Address;
import com.google.gerrit.entities.EmailHeader;
import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.Test;

public class MailTest {
  @Test
  public void composesHeadersAndBase64Utf8Body() throws Exception {
    Map<String, EmailHeader> headers = new LinkedHashMap<>();
    headers.put("Subject", new EmailHeader.StringEmailHeader("[Gerrit] Merged: p #1"));
    String raw = Mailer.compose(headers, "Merged by Zoë\n\n.\nend\n");
    assertTrue(raw.startsWith("Subject: [Gerrit] Merged: p #1\r\n"));
    assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8\r\n"));
    String body = raw.split("\r\n\r\n", 2)[1].trim();
    assertEquals(
        "Merged by Zoë\n\n.\nend\n",
        new String(Base64.getMimeDecoder().decode(body), StandardCharsets.UTF_8));
  }

  /** Minimal plain SMTP server: records the envelope and message, accepts everything. */
  @Test
  public void smtpTransportSpeaksPlainSmtp() throws Exception {
    try (var server = new ServerSocket(0)) {
      var log = new CompletableFuture<List<String>>();
      var thread =
          new Thread(() -> {
            List<String> lines = new ArrayList<>();
            try (var socket = server.accept();
                var in =
                    new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var out =
                    new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
              out.print("220 test\r\n");
              out.flush();
              boolean data = false;
              for (String line; (line = in.readLine()) != null; ) {
                lines.add(line);
                if (data) {
                  if (line.equals(".")) {
                    data = false;
                    out.print("250 queued\r\n");
                  }
                } else if (line.startsWith("EHLO")) out.print("250-test\r\n250 OK\r\n");
                else if (line.equals("DATA")) {
                  data = true;
                  out.print("354 go\r\n");
                } else if (line.equals("QUIT")) {
                  out.print("221 bye\r\n");
                  out.flush();
                  break;
                } else out.print("250 OK\r\n");
                out.flush();
              }
            } catch (IOException e) {
              log.completeExceptionally(e);
            }
            log.complete(lines);
          });
      thread.start();
      new SmtpTransport("localhost", server.getLocalPort(), "none", null, null, 5_000)
          .send(
              Address.parse("Gerrit <gerrit@example.com>"),
              List.of(Address.parse("ada@example.com")),
              "Subject: hi\r\n\r\nbody\r\n");
      var lines = log.get(10, TimeUnit.SECONDS);
      assertTrue(lines.contains("MAIL FROM:<gerrit@example.com>"));
      assertTrue(lines.contains("RCPT TO:<ada@example.com>"));
      assertTrue(lines.contains("Subject: hi"));
      assertEquals("QUIT", lines.get(lines.size() - 1));
    }
  }

  @Test
  public void unconfiguredTransportSendsNothing() {
    assertFalse(new SmtpTransport(null, 25, "none", null, null, 5_000).configured());
  }

  @Test
  public void silentServerDuringSslHandshakeTimesOut() throws Exception {
    try (var server = new ServerSocket(0)) {
      var accepted = new ArrayList<java.net.Socket>(); // keep the connection open, say nothing
      var thread =
          new Thread(() -> {
            try {
              accepted.add(server.accept());
            } catch (IOException e) {
              // test ends
            }
          });
      thread.start();
      var transport = new SmtpTransport("localhost", server.getLocalPort(), "ssl", null, null, 300);
      var attempt =
          CompletableFuture.runAsync(() -> {
            try {
              transport.send(
                  Address.parse("gerrit@example.com"),
                  List.of(Address.parse("ada@example.com")),
                  "x");
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
      try {
        attempt.get(5, TimeUnit.SECONDS); // TimeoutException here means the worker would hang
        fail("expected the stalled handshake to fail");
      } catch (ExecutionException e) {
        assertTrue(e.getCause() instanceof UncheckedIOException);
      }
      for (var socket : accepted) socket.close();
    }
  }

  // ---- TLS / authentication against a local server with a throwaway certificate ----

  private static final char[] STORE_PASS = "changeit".toCharArray();

  /** Self-signed key pair for {@code host}, generated with the JDK's keytool (no extra deps). */
  private static KeyStore keyStore(String host) throws Exception {
    Path file = Files.createTempDirectory("smtp-tls").resolve("server.p12");
    var keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "smtp",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=" + host,
                "-ext",
                "SAN=dns:" + host,
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                file.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    keytool.getInputStream().readAllBytes();
    assertEquals(0, keytool.waitFor());
    var store = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(file)) {
      store.load(in, STORE_PASS);
    }
    return store;
  }

  private static SSLContext serverContext(KeyStore store) throws Exception {
    var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, STORE_PASS);
    var ctx = SSLContext.getInstance("TLS");
    ctx.init(keys.getKeyManagers(), null, null);
    return ctx;
  }

  /** A client context trusting exactly this certificate (stands in for a real CA). */
  private static SSLContext trusting(KeyStore store) throws Exception {
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(store);
    var ctx = SSLContext.getInstance("TLS");
    ctx.init(null, trust.getTrustManagers(), null);
    return ctx;
  }

  /** Local SMTP server with STARTTLS and AUTH PLAIN; logs every line it receives. */
  private static final class SmtpServer implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final CompletableFuture<List<String>> log = new CompletableFuture<>();

    SmtpServer(SSLContext tls) throws IOException {
      new Thread(() -> serve(tls)).start();
    }

    private static BufferedReader reader(java.net.Socket s) throws IOException {
      return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private static Writer writer(java.net.Socket s) throws IOException {
      return new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
    }

    private static void reply(Writer out, String text) throws IOException {
      out.write(text + "\r\n");
      out.flush();
    }

    private void serve(SSLContext tls) {
      List<String> lines = new ArrayList<>();
      try (var plain = socket.accept()) {
        java.net.Socket s = plain;
        var in = reader(s);
        var out = writer(s);
        reply(out, "220 test");
        boolean data = false, credentials = false;
        for (String line; (line = in.readLine()) != null; ) {
          lines.add(line);
          String cmd = line.toUpperCase(Locale.ROOT);
          if (data) {
            if (line.equals(".")) {
              data = false;
              reply(out, "250 queued");
            }
          } else if (credentials) {
            credentials = false;
            reply(out, "235 authenticated");
          } else if (cmd.startsWith("EHLO")) {
            reply(out, "250-test\r\n250-STARTTLS\r\n250 AUTH PLAIN");
          } else if (cmd.equals("STARTTLS")) {
            reply(out, "220 go ahead");
            var secure =
                (SSLSocket)
                    tls.getSocketFactory()
                        .createSocket(s, s.getInetAddress().getHostAddress(), s.getPort(), false);
            secure.setUseClientMode(false);
            secure.startHandshake(); // throws when the client rejects the certificate
            s = secure;
            in = reader(s);
            out = writer(s);
            lines.add("<encrypted>");
          } else if (cmd.startsWith("AUTH PLAIN")) {
            if (line.trim().length() > "AUTH PLAIN".length()) reply(out, "235 authenticated");
            else {
              credentials = true;
              reply(out, "334 ");
            }
          } else if (cmd.equals("DATA")) {
            data = true;
            reply(out, "354 go");
          } else if (cmd.equals("QUIT")) {
            reply(out, "221 bye");
            break;
          } else reply(out, "250 OK");
        }
      } catch (IOException e) {
        lines.add("<closed: " + e.getClass().getSimpleName() + ">");
      }
      log.complete(lines);
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
  }

  private static final String SECRET_PLAIN =
      Base64.getEncoder().encodeToString("\0gerrit\0s3cret".getBytes(StandardCharsets.UTF_8));

  private static void send(SmtpTransport transport) throws IOException {
    transport.send(
        Address.parse("gerrit@example.com"),
        List.of(Address.parse("ada@example.com")),
        "Subject: hi\r\n\r\nbody\r\n");
  }

  @Test
  public void startTlsThenAuthenticatesAndDeliversOverEncryption() throws Exception {
    var store = keyStore("localhost");
    try (var server = new SmtpServer(serverContext(store))) {
      send(
          new SmtpTransport(
              "localhost",
              server.socket.getLocalPort(),
              "tls",
              "gerrit",
              "s3cret",
              5_000,
              trusting(store)));
      var lines = server.log.get(10, TimeUnit.SECONDS);
      int encrypted = lines.indexOf("<encrypted>");
      assertTrue("STARTTLS negotiated: " + lines, encrypted > lines.indexOf("STARTTLS"));
      int auth = indexOf(lines, l -> l.contains(SECRET_PLAIN));
      assertTrue("credentials only after encryption: " + lines, auth > encrypted);
      assertTrue(lines.indexOf("MAIL FROM:<gerrit@example.com>") > auth);
      assertTrue(lines.indexOf("RCPT TO:<ada@example.com>") > auth);
      assertTrue(lines.indexOf("Subject: hi") > auth);
    }
  }

  @Test
  public void certificateForAnotherHostIsRejectedBeforeAuthentication() throws Exception {
    var store = keyStore("wrong.example"); // trusted, but not issued for localhost
    try (var server = new SmtpServer(serverContext(store))) {
      assertRejected(
          server,
          new SmtpTransport(
              "localhost",
              server.socket.getLocalPort(),
              "tls",
              "gerrit",
              "s3cret",
              5_000,
              trusting(store)));
    }
  }

  @Test
  public void untrustedCertificateIsRejectedByTheDefaultTrustStore() throws Exception {
    var store = keyStore("localhost"); // right name, but self-signed: production trust path
    try (var server = new SmtpServer(serverContext(store))) {
      assertRejected(
          server,
          new SmtpTransport(
              "localhost", server.socket.getLocalPort(), "tls", "gerrit", "s3cret", 5_000));
    }
  }

  private static void assertRejected(SmtpServer server, SmtpTransport transport) throws Exception {
    try {
      send(transport);
      fail("TLS with an invalid certificate must fail");
    } catch (IOException expected) {
      // rejected by the client
    }
    var lines = server.log.get(10, TimeUnit.SECONDS);
    assertTrue(lines.contains("STARTTLS"));
    assertFalse("never encrypted: " + lines, lines.contains("<encrypted>"));
    for (String line : lines) {
      assertFalse("no AUTH: " + lines, line.toUpperCase(Locale.ROOT).startsWith("AUTH"));
      assertFalse("no credentials: " + lines, line.contains(SECRET_PLAIN));
      assertFalse("no delivery: " + lines, line.startsWith("MAIL") || line.startsWith("RCPT"));
      assertFalse("no message: " + lines, line.contains("Subject"));
    }
  }

  private static int indexOf(List<String> lines, java.util.function.Predicate<String> p) {
    for (int i = 0; i < lines.size(); i++) if (p.test(lines.get(i))) return i;
    return -1;
  }
}
