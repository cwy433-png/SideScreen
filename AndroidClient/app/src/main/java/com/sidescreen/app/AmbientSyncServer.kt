package com.sidescreen.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * App-private ambient media receiver bound only to 127.0.0.1:[AmbientSyncProtocol.DEVICE_PORT].
 * Accepts one connection at a time; failures never mutate the committed batch.
 *
 * [onCommitted] is invoked only after a successful staging commit. [preferredMode] is
 * non-null only when the manifest requested a mode switch; clear / legacy / failed
 * transfers do not call with a mode (failed transfers never call at all).
 */
class AmbientSyncServer(
    private val store: AmbientMediaStore,
    private val port: Int = AmbientSyncProtocol.DEVICE_PORT,
    private val onCommitted: (
        summary: String,
        preferredMode: AmbientSyncProtocol.PreferredMode?,
    ) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientLock = Any()
    private var activeClient: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        store.cleanStaleStaging()
        try {
            val ss =
                ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).also {
                    it.reuseAddress = true
                    it.soTimeout = 0
                }
            serverSocket = ss
            acceptThread =
                thread(name = "AmbientSyncServer", isDaemon = true) {
                    acceptLoop(ss)
                }
            DiagLog.log("AS", "AmbientSyncServer listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            running.set(false)
            DiagLog.log("AS", "AmbientSyncServer start failed: ${e.message}")
        }
    }

    /**
     * Stop the listener and any in-flight client. Does not block the Android main
     * thread indefinitely: closes sockets so accept/IO unblock, then joins the
     * daemon accept thread for a short bound when called off that thread.
     * After stop, [onCommitted] is never invoked (even if a transfer finishes late).
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // ignore
        }
        serverSocket = null
        synchronized(clientLock) {
            try {
                activeClient?.close()
            } catch (_: Exception) {
                // ignore
            }
            activeClient = null
        }
        val t = acceptThread
        acceptThread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        DiagLog.log("AS", "AmbientSyncServer stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val client = ss.accept()
                if (!running.get()) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                    break
                }
                synchronized(clientLock) {
                    activeClient = client
                }
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    DiagLog.log("AS", "client error: ${e.message}")
                } finally {
                    synchronized(clientLock) {
                        if (activeClient === client) {
                            activeClient = null
                        }
                    }
                    try {
                        client.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            } catch (_: SocketException) {
                if (!running.get()) break
            } catch (e: Exception) {
                if (!running.get()) break
                DiagLog.log("AS", "accept error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 120_000
        val input = BufferedInputStream(socket.getInputStream(), AmbientSyncProtocol.IO_CHUNK_BYTES)
        val output = BufferedOutputStream(socket.getOutputStream(), 1024)

        try {
            // Header: magic(4) + version(1) + manifestLen(u32 BE)
            val header = readExact(input, 9)
            if (!AmbientSyncProtocol.isValidMagic(header[0], header[1], header[2], header[3])) {
                writeAck(output, false, "invalid magic")
                return
            }
            if (header[4] != AmbientSyncProtocol.VERSION) {
                writeAck(output, false, "unsupported version")
                return
            }
            val manifestLen =
                AmbientSyncProtocol
                    .readUInt32BE(header[5], header[6], header[7], header[8])
                    .toInt()
            if (manifestLen < 0 || manifestLen > AmbientSyncProtocol.MAX_MANIFEST_BYTES) {
                writeAck(output, false, "manifest too large")
                return
            }

            val manifestBytes = readExact(input, manifestLen)
            val manifestJson = String(manifestBytes, Charsets.UTF_8)
            val manifest =
                try {
                    AmbientSyncProtocol.parseManifestJson(manifestJson)
                } catch (e: AmbientSyncProtocol.ProtocolError) {
                    writeAck(output, false, e.message ?: "bad manifest")
                    return
                }

            // Empty manifest = clear tablet media via the same staging/commit path
            // (commits an empty current batch; loadBatch exposes zero assets → SAF fallback).
            val session = store.beginStaging(manifest)
            try {
                for (entry in manifest.entries) {
                    if (!running.get()) {
                        session.abort()
                        return
                    }
                    session.receiveEntry(entry) { buffer, max ->
                        readInto(input, buffer, max)
                    }
                }
                if (!running.get()) {
                    session.abort()
                    return
                }
                val summary = session.commit()
                // Mode is applied only after successful commit (callback on main thread).
                // Clear and legacy manifests leave preferredMode null → mode unchanged.
                // Deliver callback even if the subsequent ACK write fails so mode still applies.
                val appliedMode = if (manifest.entries.isEmpty()) null else manifest.preferredMode
                val text =
                    if (manifest.entries.isEmpty()) {
                        "cleared"
                    } else {
                        val base = summary.compact()
                        if (appliedMode != null) {
                            "$base · mode ${appliedMode.wire}"
                        } else {
                            base
                        }
                    }
                notifyCommitted(text, appliedMode)
                DiagLog.log("AS", "committed: $text")
                try {
                    writeAck(output, true, text)
                } catch (e: Exception) {
                    DiagLog.log("AS", "ACK after commit failed (media already committed): ${e.message}")
                }
            } catch (e: AmbientSyncProtocol.ProtocolError) {
                session.abort()
                writeAck(output, false, e.message ?: "transfer failed")
            } catch (e: Exception) {
                session.abort()
                writeAck(output, false, e.message ?: "transfer failed")
            }
        } catch (e: EOFException) {
            DiagLog.log("AS", "truncated connection")
            try {
                writeAck(output, false, "truncated")
            } catch (_: Exception) {
                // ignore
            }
        } catch (e: Exception) {
            DiagLog.log("AS", "handle failed: ${e.message}")
            try {
                writeAck(output, false, e.message ?: "error")
            } catch (_: Exception) {
                // ignore
            }
        } finally {
            try {
                output.flush()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /** Deliver commit callback only while the server is still running. */
    private fun notifyCommitted(
        summary: String,
        preferredMode: AmbientSyncProtocol.PreferredMode?,
    ) {
        synchronized(clientLock) {
            if (!running.get()) {
                DiagLog.log("AS", "suppress late commit callback after stop: $summary")
                return
            }
            onCommitted(summary, preferredMode)
        }
    }

    private fun writeAck(out: OutputStream, ok: Boolean, message: String) {
        out.write(AmbientSyncProtocol.encodeAck(ok, message))
        out.flush()
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw EOFException("need $length got $off")
            off += n
        }
        return buf
    }

    /** Read exactly [max] bytes into [buffer][0..max), or fewer only on EOF mid-chunk. */
    private fun readInto(input: InputStream, buffer: ByteArray, max: Int): Int {
        var off = 0
        while (off < max) {
            val n = input.read(buffer, off, max - off)
            if (n < 0) {
                if (off == 0) return -1
                throw EOFException("truncated body")
            }
            off += n
        }
        return off
    }
}
