package com.snaix.pikpak

import android.content.Context
import android.net.Uri
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

internal fun loopbackTls(test: Context): SSLContext {
    val keys = KeyStore.getInstance("PKCS12").apply {
        val id = test.resources.getIdentifier("subtitle_localhost", "raw", test.packageName)
        test.resources.openRawResource(id).use { load(it, "fixture".toCharArray()) }
    }
    // Trust only the test APK's loopback certificate while Media3 reads the sidecar.
    val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("loopback", keys.getCertificate("fixture"))
    }
    val keysFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
        init(keys, "fixture".toCharArray())
    }
    val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
    return SSLContext.getInstance("TLS").apply { init(keysFactory.keyManagers, trustFactory.trustManagers, null) }
}

// Count real media requests independently of the player, including requests from a replacement player.
internal fun withNetworkVideo(test: Context, run: (Uri, AtomicInteger) -> Unit) {
    val tls = loopbackTls(test)
    val id = test.resources.getIdentifier("gesture_playback", "raw", test.packageName)
    val bytes = test.resources.openRawResource(id).use { it.readBytes() }
    val requests = AtomicInteger()
    val loopback = InetAddress.getByName("127.0.0.1")
    tls.serverSocketFactory.createServerSocket(0, 4, loopback).use { server ->
        val worker = thread(name = "PiP video fixture", isDaemon = true) {
            while (!server.isClosed) {
                val connection = try { server.accept() } catch (error: SocketException) {
                    if (server.isClosed) break else throw error
                }
                connection.use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    if (input.readLine() != "GET /video.mp4 HTTP/1.1") return@use
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    val range = headers.firstOrNull { it.startsWith("Range: bytes=", ignoreCase = true) }
                    val offset = range?.substringAfter('=')?.substringBefore('-')?.toIntOrNull() ?: 0
                    require(offset in bytes.indices)
                    requests.incrementAndGet()
                    val response = buildString {
                        append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
                        if (range != null) append("Content-Range: bytes $offset-${bytes.lastIndex}/${bytes.size}\r\n")
                        append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                        append("Content-Length: ${bytes.size - offset}\r\nConnection: close\r\n\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.US_ASCII))
                        write(bytes, offset, bytes.size - offset)
                        flush()
                    }
                }
            }
        }
        val previous = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            run(Uri.parse("https://127.0.0.1:${server.localPort}/video.mp4"), requests)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous)
            server.close()
            worker.join(5_000)
        }
    }
}
