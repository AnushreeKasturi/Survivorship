package com.startupsurvival.web

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.net.{InetSocketAddress, Socket, URI}
import java.nio.charset.StandardCharsets.UTF_8

/**
 * GET and SET against a Redis-compatible store (Render Key Value) over the
 * RESP protocol, one short connection per call. The watchlist writes only
 * when a visitor adds a startup, so pooling and a client library aren't
 * worth it. Plain redis:// only - Render's internal URL needs no TLS.
 */
final class Store(url: String) {
  private val uri = new URI(url)
  require(uri.getScheme == "redis", "KV_URL must be a redis:// URL")
  private val password = Option(uri.getUserInfo).map(_.split(':').last).filter(_.nonEmpty)

  private def line(in: InputStream): String = {
    val b = new ByteArrayOutputStream()
    var c = in.read()
    while (c != '\r' && c != -1) { b.write(c); c = in.read() }
    in.read() // '\n'
    b.toString(UTF_8.name)
  }

  /** Sends one command and returns a bulk/simple string reply, None for nil. */
  private def run(os: OutputStream, in: InputStream, args: String*): Option[String] = {
    val out = new ByteArrayOutputStream()
    out.write(s"*${args.length}\r\n".getBytes(UTF_8))
    args.foreach { a =>
      val bytes = a.getBytes(UTF_8)
      out.write(s"$$${bytes.length}\r\n".getBytes(UTF_8)); out.write(bytes); out.write("\r\n".getBytes(UTF_8))
    }
    os.write(out.toByteArray)
    val reply = line(in)
    reply.headOption match {
      case Some('-') => throw new IllegalStateException(s"key-value store: ${reply.drop(1)}")
      case Some('$') if reply == "$-1" => None
      case Some('$') =>
        val n = reply.drop(1).toInt
        val data = new Array[Byte](n)
        var read = 0
        while (read < n) read += in.read(data, read, n - read)
        line(in)
        Some(new String(data, UTF_8))
      case _ => Some(reply.drop(1))
    }
  }

  private def call(args: String*): Option[String] = {
    val socket = new Socket()
    try {
      socket.connect(new InetSocketAddress(uri.getHost, if (uri.getPort > 0) uri.getPort else 6379), 3000)
      socket.setSoTimeout(3000)
      val in = new BufferedInputStream(socket.getInputStream)
      val os = socket.getOutputStream
      password.foreach(p => run(os, in, "AUTH", p))
      run(os, in, args: _*)
    } finally socket.close()
  }

  def get(key: String): Option[String] = call("GET", key)
  def set(key: String, value: String): Unit = call("SET", key, value)
}
