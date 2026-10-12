package app.tvremote

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Маленький HTTP-сервер прямо в приложении ТВ, без внешних библиотек.
 *
 *  GET /     — страница пульта (assets/index.html)
 *  GET /ws   — WebSocket: первым сообщением страница шлёт {"t":"auth","c":"<код>"},
 *              дальше команды {"t":"key"|"text"|"ping", ...}; ТВ отвечает статусом.
 *
 * Работает только в локальной сети: ни интернет, ни брокер не нужны.
 */
class WebServer(
    private val ctx: Context,
    private val code: String,
    private val onCommand: (JSONObject) -> Unit,
    private val statusJson: () -> String
) {
    companion object {
        const val FIRST_PORT = 8080
        private const val PORT_TRIES = 10
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_CONNECTIONS = 32
        private const val MAX_FRAME = 65536
        private const val AUTH_TIMEOUT_MS = 8000
        private const val IDLE_TIMEOUT_MS = 60000

        fun normCode(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
    }

    private val pool = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArraySet<WsConn>()
    private val open = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile var port: Int = 0
        private set

    val clientCount: Int get() = clients.size

    /** Поднимает сервер на первом свободном порту 8080..8089. Бросает исключение, если все заняты. */
    @Synchronized
    fun start() {
        if (running) return
        var last: Throwable? = null
        for (p in FIRST_PORT until FIRST_PORT + PORT_TRIES) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(p))
                server = s
                port = p
                running = true
                pool.execute { acceptLoop(s) }
                return
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException("порты $FIRST_PORT–${FIRST_PORT + PORT_TRIES - 1} заняты: ${last?.message}")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        server = null
        for (c in clients) c.close()
        clients.clear()
        pool.shutdownNow()
    }

    /** Разослать текущий статус всем подключённым страницам. */
    fun broadcastStatus() {
        if (clients.isEmpty()) return
        val s = statusJson()
        for (c in clients) c.trySend(s)
    }

    /** Разослать всем подключённым страницам произвольное сообщение (JSON). */
    fun broadcastRaw(s: String) {
        for (c in clients) c.trySend(s)
    }

    // ---------- приём соединений ----------

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock = try {
                s.accept()
            } catch (_: Throwable) {
                break
            }
            if (open.incrementAndGet() > MAX_CONNECTIONS) {
                open.decrementAndGet()
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(sock)
                    } catch (_: Throwable) {
                    } finally {
                        open.decrementAndGet()
                        try {
                            sock.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
                open.decrementAndGet()
                try {
                    sock.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---------- HTTP ----------

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun handle(sock: Socket) {
        sock.tcpNoDelay = true
        sock.soTimeout = 10000
        val inp = sock.getInputStream()
        val out = BufferedOutputStream(sock.getOutputStream())

        val reqLine = readLine(inp) ?: return
        val parts = reqLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") {
            respond(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "405".toByteArray())
            return
        }
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 64) return
        }

        val isWs = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
        when {
            isWs && path == "/ws" -> webSocket(sock, inp, out, headers)
            path == "/" || path == "/index.html" -> {
                val page = try {
                    ctx.assets.open("index.html").use { it.readBytes() }
                } catch (_: Throwable) {
                    respond(out, 500, "Internal Server Error", "text/plain; charset=utf-8", "no page".toByteArray())
                    return
                }
                respond(out, 200, "OK", "text/html; charset=utf-8", page)
            }
            path == "/favicon.ico" -> respond(out, 204, "No Content", "text/plain", ByteArray(0))
            else -> respond(out, 404, "Not Found", "text/plain; charset=utf-8", "404".toByteArray())
        }
    }

    private fun respond(out: BufferedOutputStream, status: Int, text: String, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $status $text\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ---------- WebSocket ----------

    private class WsConn(val sock: Socket, val out: BufferedOutputStream) {
        @Volatile var authed = false

        fun sendFrame(op: Int, data: ByteArray) {
            synchronized(out) {
                out.write(0x80 or op)
                when {
                    data.size < 126 -> out.write(data.size)
                    data.size < 65536 -> {
                        out.write(126)
                        out.write(data.size shr 8)
                        out.write(data.size and 0xFF)
                    }
                    else -> throw IOException("frame too large")
                }
                out.write(data)
                out.flush()
            }
        }

        fun sendText(s: String) = sendFrame(0x1, s.toByteArray(Charsets.UTF_8))

        fun trySend(s: String) {
            try {
                sendText(s)
            } catch (_: Throwable) {
                close()
            }
        }

        fun close() {
            try {
                sock.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun webSocket(sock: Socket, inp: InputStream, out: BufferedOutputStream, h: Map<String, String>) {
        val key = h["sec-websocket-key"]
        if (key == null || h["sec-websocket-version"] != "13") {
            respond(out, 400, "Bad Request", "text/plain; charset=utf-8", "400".toByteArray())
            return
        }
        // Защита от чужих сайтов: страница должна быть открыта с ТВ (любой его адрес) или из домашней сети.
        // Страница сама ищет ТВ по сети, если его адрес сменился, поэтому адреса вида 192.168.x.x / 10.x.x.x допустимы.
        // Доступ всё равно закрыт кодом.
        val origin = h["origin"]
        if (origin != null) {
            val ok = try {
                val u = URI(origin)
                u.authority == h["host"] || isPrivateHost(u.host)
            } catch (_: Throwable) {
                false
            }
            if (!ok) {
                respond(out, 403, "Forbidden", "text/plain; charset=utf-8", "403".toByteArray())
                return
            }
        }

        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        out.flush()

        val c = WsConn(sock, out)
        sock.soTimeout = AUTH_TIMEOUT_MS
        try {
            readFrames(c, inp)
        } finally {
            clients.remove(c)
            c.close()
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = inp.read(buf, n, buf.size - n)
            if (r < 0) throw EOFException()
            n += r
        }
    }

    private fun readFrames(c: WsConn, inp: InputStream) {
        val msg = ByteArrayOutputStream()
        var msgOp = 0
        try {
            while (running) {
                val b0 = inp.read()
                val b1 = inp.read()
                if (b0 < 0 || b1 < 0) return
                val fin = (b0 and 0x80) != 0
                val op = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    val e = ByteArray(2)
                    readFully(inp, e)
                    len = (((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF)).toLong()
                } else if (len == 127L) {
                    val e = ByteArray(8)
                    readFully(inp, e)
                    len = 0
                    for (x in e) len = (len shl 8) or (x.toLong() and 0xFF)
                }
                if (len < 0 || len > MAX_FRAME) return
                val mask = ByteArray(4)
                if (masked) readFully(inp, mask)
                val data = ByteArray(len.toInt())
                readFully(inp, data)
                if (masked) {
                    for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
                }

                when (op) {
                    0x8 -> {
                        try {
                            c.sendFrame(0x8, ByteArray(0))
                        } catch (_: Throwable) {
                        }
                        return
                    }
                    0x9 -> c.sendFrame(0xA, data)
                    0xA -> {}
                    0x0, 0x1, 0x2 -> {
                        if (op != 0x0) {
                            msg.reset()
                            msgOp = op
                        }
                        msg.write(data)
                        if (msg.size() > MAX_FRAME) return
                        if (fin) {
                            val text = if (msgOp == 0x1) String(msg.toByteArray(), Charsets.UTF_8) else null
                            msg.reset()
                            if (text != null && !onText(c, text)) return
                        }
                    }
                    else -> return
                }
            }
        } catch (_: SocketTimeoutException) {
            // нет ни авторизации, ни пинга от страницы — считаем соединение мёртвым
        }
    }

    /** false — соединение нужно закрыть. */
    private fun onText(c: WsConn, text: String): Boolean {
        val j = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return c.authed
        }
        if (!c.authed) {
            if (j.optString("t") == "auth" && codeMatches(j.optString("c"))) {
                c.authed = true
                c.sock.soTimeout = IDLE_TIMEOUT_MS
                clients.add(c)
                c.sendText(statusJson())
                return true
            }
            // неверный код: небольшая пауза против перебора
            try {
                c.sendText("{\"t\":\"denied\"}")
                Thread.sleep(1000)
            } catch (_: Throwable) {
            }
            return false
        }
        when (j.optString("t")) {
            "ping" -> c.sendText(statusJson())
            else -> onCommand(j)
        }
        return true
    }

    private fun isPrivateHost(host: String?): Boolean {
        if (host == null) return false
        val p = host.split('.')
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
    }

    private fun codeMatches(given: String): Boolean {
        val a = normCode(given).toByteArray()
        val b = normCode(code).toByteArray()
        return MessageDigest.isEqual(a, b)
    }
}
