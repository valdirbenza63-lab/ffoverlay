package com.zyon.ffoverlay

import android.content.Context
import android.graphics.*
import android.view.View
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class OverlayView(context: Context) : View(context) {
    private val headerSize = 4 + 4 + 12 + 12 + 4
    private val playerSize = 4 + 4 + 12 + 12
    private val maxPlayers = 64
    private val bufSize = headerSize + playerSize * maxPlayers + 8
    private val buf = ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN)

    private val paintBox = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.GREEN; isAntiAlias = true }
    private val paintHpBg = Paint().apply { style = Paint.Style.FILL; color = Color.argb(180, 0, 0, 0) }
    private val paintHpFg = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }

    private var players: List<Player> = emptyList()
    private var camLoc = Vec3(); private var camRot = Vec3(); private var camFov = 90f
    private var screenW = 0; private var screenH = 0

    private data class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f)
    private data class Player(var team: Int = 0, var hp: Int = 0, var head: Vec3 = Vec3(), var origin: Vec3 = Vec3())

    init {
        thread(name = "shm-reader") {
            val file = try { RandomAccessFile("/dev/shm/ff_state", "r") } catch (e: Exception) { null }
            while (!isDetached) {
                if (file != null) {
                    try {
                        file.seek(0); buf.clear()
                        if (file.channel.read(buf) >= headerSize) parse()
                        postInvalidate()
                    } catch (_: Exception) {}
                }
                Thread.sleep(16)
            }
            file?.close()
        }
    }

    private fun parse() {
        buf.position(0)
        if (buf.int != 0x46464854) return
        val count = buf.int.coerceIn(0, maxPlayers)
        camLoc = Vec3(buf.float, buf.float, buf.float)
        camRot = Vec3(buf.float, buf.float, buf.float)
        camFov = buf.float
        val list = ArrayList<Player>(count)
        for (i in 0 until count) {
            val p = Player()
            p.team = buf.int; p.hp = buf.int
            p.head = Vec3(buf.float, buf.float, buf.float)
            p.origin = Vec3(buf.float, buf.float, buf.float)
            list.add(p)
        }
        players = list
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { screenW = w; screenH = h }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (screenW == 0 || screenH == 0) return
        for (p in players) {
            val head = worldToScreen(p.head) ?: continue
            val feet = worldToScreen(p.origin) ?: continue
            val h = feet.second - head.second
            if (h <= 4) continue
            val w = h * 0.55f
            val x = head.first - w / 2
            canvas.drawRect(x, head.second, x + w, feet.second, paintBox)
            val hp = (p.hp / 100f).coerceIn(0f, 1f)
            canvas.drawRect(x - 8, head.second, x - 4, feet.second, paintHpBg)
            canvas.drawRect(x - 8, feet.second - h * hp, x - 4, feet.second, paintHpFg)
        }
    }

    private fun worldToScreen(w: Vec3): Pair<Float, Float>? {
        val dx = w.x - camLoc.x; val dy = w.y - camLoc.y; val dz = w.z - camLoc.z
        val pitch = Math.toRadians(camRot.x.toDouble()).toFloat()
        val yaw = Math.toRadians(camRot.y.toDouble()).toFloat()
        val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
        val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
        val x1 = dx * cy - dz * sy
        val z1 = dx * sy + dz * cy
        val y1 = dy * cp - z1 * sp
        val z2 = dy * sp + z1 * cp
        if (z2 < 1f) return null
        val focal = (screenH * 0.5f) / kotlin.math.tan(Math.toRadians(camFov.toDouble() / 2.0).toFloat())
        val sx = (screenW * 0.5f) + (x1 / z2) * focal
        val sy2 = (screenH * 0.5f) - (y1 / z2) * focal
        if (sx < 0 || sx >= screenW || sy2 < 0 || sy2 >= screenH) return null
        return sx to sy2
    }
}
