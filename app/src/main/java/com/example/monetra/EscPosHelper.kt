package com.example.monetra

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 打印风格枚举：支持自定义对比度和亮度预设
 */
enum class PrintStyle(
    val description: String,
    val contrast: Float = 1.0f,
    val brightness: Int = 0
) {
    ATKINSON("复古标准 (Atkinson)", 1.5f, 0),
    ATKINSON_SOFT("复古柔和 (低对比)", 0.8f, 15),
    ATKINSON_HARD("复古强力 (高对比)", 2.5f, -10),
    FLOYD("细腻写实 (Floyd)", 1.3f, 5),
    THRESHOLD("纯净黑白 (二值)", 2.0f, 0),
    BAYER("报纸点阵 (Bayer)", 1.2f, 10)
}

object EscPosHelper {

    // --- ESC/POS 指令集 ---
    val initPrinter = byteArrayOf(0x1B, 0x40)
    val alignCenter = byteArrayOf(0x1B, 0x61, 0x01)
    val alignLeft = byteArrayOf(0x1B, 0x61, 0x00)
    val boldOn = byteArrayOf(0x1B, 0x45, 0x01)
    val boldOff = byteArrayOf(0x1B, 0x45, 0x00)
    val fontSizeLarge = byteArrayOf(0x1D, 0x21, 0x22) // 3x3 倍率
    val fontSizeNormal = byteArrayOf(0x1D, 0x21, 0x00)
    
    fun setLeftMargin(dots: Int) = byteArrayOf(0x1D, 0x4C, (dots % 256).toByte(), (dots / 256).toByte())
    fun feedLines(n: Int) = byteArrayOf(0x1B, 0x64, n.toByte())

    /**
     * 核心图像处理：抖动算法 + 打印指令生成
     */
    fun processBitmap(bmp: Bitmap, style: PrintStyle, previewMode: Boolean = false): Any {
        val width = bmp.width
        val height = bmp.height
        val size = width * height
        val pixels = IntArray(size)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val grayScale = IntArray(size)
        
        // 1. 灰度化 + 动态对比度 + 动态亮度
        for (i in 0 until size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            var gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
            gray = (gray + style.brightness).coerceIn(0, 255)
            gray = ((gray - 128) * style.contrast + 128).toInt().coerceIn(0, 255)
            grayScale[i] = gray
        }

        // 2. 应用抖动算法
        when {
            style.name.contains("ATKINSON") -> applyAtkinson(grayScale, width, height)
            style.name.contains("FLOYD") -> applyFloyd(grayScale, width, height)
            style.name.contains("THRESHOLD") -> applyThreshold(grayScale)
            style.name.contains("BAYER") -> applyBayer(grayScale, width, height)
        }

        if (previewMode) {
            val resultPixels = IntArray(size)
            for (i in 0 until size) {
                resultPixels[i] = if (grayScale[i] < 128) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(resultPixels, width, height, Bitmap.Config.ARGB_8888)
        }

        // 3. 生成 GS v 0 打印指令
        val paddedWidth = (width + 7) / 8 * 8
        val data = ByteArray(paddedWidth * height / 8)
        var k = 0
        for (y in 0 until height) {
            for (x in 0 until paddedWidth step 8) {
                var temp = 0
                for (t in 0 until 8) {
                    val targetX = x + t
                    if (targetX < width) {
                        if (grayScale[y * width + targetX] < 128) {
                            temp = temp or (1 shl (7 - t))
                        }
                    }
                }
                data[k++] = temp.toByte()
            }
        }
        val xL = (paddedWidth / 8) % 256
        val xH = (paddedWidth / 8) / 256
        val yL = height % 256
        val yH = height / 256
        
        val bitmapCommand = byteArrayOf(0x1D, 0x76, 0x30, 0x00, xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte()) + data
        
        // 返回 居中 + 位图数据 + 恢复左对齐
        return alignCenter + bitmapCommand + alignLeft
    }

    private fun applyAtkinson(gs: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val oldP = gs[idx]
                val newP = if (oldP < 128) 0 else 255
                gs[idx] = newP
                val err = (oldP - newP) shr 3
                if (x + 1 < w) gs[idx + 1] += err
                if (x + 2 < w) gs[idx + 2] += err
                if (y + 1 < h) {
                    val nextRow = row + w
                    if (x > 0) gs[nextRow + x - 1] += err
                    gs[nextRow + x] += err
                    if (x + 1 < w) gs[nextRow + x + 1] += err
                    if (y + 2 < h) gs[row + 2 * w + x] += err
                }
            }
        }
    }

    private fun applyFloyd(gs: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val idx = row + x
                val oldP = gs[idx]
                val newP = if (oldP < 128) 0 else 255
                gs[idx] = newP
                val err = oldP - newP
                if (x + 1 < w) gs[idx + 1] += err * 7 / 16
                if (y + 1 < h) {
                    val nextRow = row + w
                    if (x > 0) gs[nextRow + x - 1] += err * 3 / 16
                    gs[nextRow + x] += err * 5 / 16
                    if (x + 1 < w) gs[nextRow + x + 1] += err * 1 / 16
                }
            }
        }
    }

    private fun applyThreshold(gs: IntArray) {
        for (i in gs.indices) gs[i] = if (gs[i] < 128) 0 else 255
    }

    private fun applyBayer(gs: IntArray, w: Int, h: Int) {
        val bayer = intArrayOf(0, 32, 8, 40, 48, 16, 56, 24, 12, 44, 4, 36, 60, 28, 52, 20)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val threshold = bayer[(y % 4) * 4 + (x % 4)] * 4
                val idx = y * w + x
                gs[idx] = if (gs[idx] < threshold) 0 else 255
            }
        }
    }
}
