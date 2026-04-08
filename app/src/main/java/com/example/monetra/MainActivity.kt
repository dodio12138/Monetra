package com.example.monetra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private lateinit var printerStatusText: TextView
    private lateinit var previewOverlay: ImageView
    private lateinit var orderPanel: View
    private lateinit var orderStatusText: TextView
    
    private var currentStyle = PrintStyle.ATKINSON
    private var isRealtimePreviewEnabled = false
    private var printerMacAddress = "B8:32:41:12:34:56"

    // 点单逻辑变量
    private val orderCounts = mutableMapOf<String, Int>()
    private val orderHandler = Handler(Looper.getMainLooper())
    private val orderRunnable = Runnable { commitOrder() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 保持屏幕常亮
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 2. 隐藏状态栏，实现全屏沉浸感
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        setContentView(R.layout.activity_main)

        printerStatusText = findViewById(R.id.printerStatusText)
        previewOverlay = findViewById(R.id.preview_overlay)
        orderPanel = findViewById(R.id.order_panel)
        orderStatusText = findViewById(R.id.tv_order_status)
        
        findViewById<ImageButton>(R.id.capture_button).setOnClickListener { takePhoto() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { showStyleMenu() }
        findViewById<Button>(R.id.btn_reconnect).setOnClickListener { showAddressInputDialog() }

        // 点单功能绑定
        findViewById<ImageButton>(R.id.btn_open_order).setOnClickListener { orderPanel.visibility = View.VISIBLE }
        findViewById<ImageButton>(R.id.btn_close_order).setOnClickListener { orderPanel.visibility = View.GONE }
        
        setupOrderButtons()

        if (allPermissionsGranted()) {
            startCamera()
            autoConnectPrinter()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupOrderButtons() {
        val buttons = mapOf(
            R.id.btn_pork to "PORK",
            R.id.btn_buns to "BUNS",
            R.id.btn_skewer to "SKEWER",
            R.id.btn_ricecake to "RICE CAKE",
            R.id.btn_chicken to "CHICKEN",
            R.id.btn_strick to "STRICK"
        )
        buttons.forEach { (id, name) ->
            findViewById<Button>(id).setOnClickListener {
                orderCounts[name] = (orderCounts[name] ?: 0) + 1
                updateOrderStatus("已选: $name (共${orderCounts[name]}份)... 2秒后打印")
                
                // 重置计时器为 2000ms (2秒)
                orderHandler.removeCallbacks(orderRunnable)
                orderHandler.postDelayed(orderRunnable, 2000)
            }
        }
    }

    private fun updateOrderStatus(msg: String) {
        orderStatusText.text = msg
    }

    private fun commitOrder() {
        if (orderCounts.isEmpty()) return
        val currentOrder = orderCounts.toMap()
        orderCounts.clear()
        updateOrderStatus("打印中...")
        
        Thread {
            try {
                outputStream?.let { stream ->
                    // 1. 初始化
                    stream.write(EscPosHelper.initPrinter)
                    Thread.sleep(50)
                    
                    // 2. 预先开启大字体和加粗
                    stream.write(EscPosHelper.fontSizeLarge)
                    stream.write(EscPosHelper.boldOn)
                    
                    // 3. 【核心修复】先打 3 个基于大字体的“隐形”空行作为缓冲
                    // 这能强制让打印机进入大字体状态，且留出撕纸距离
                    for (i in 1..4) {
                        stream.write(" \n".toByteArray(charset("GBK")))
                    }
                    
                    // 4. 打印真正的商品
                    currentOrder.forEach { (name, count) ->
                        // 每一行都重新发送一次大字体指令（多重保险）
                        stream.write(EscPosHelper.fontSizeLarge)
                        val line = "$name X $count\n"
                        stream.write(line.toByteArray(charset("GBK")))
                        Thread.sleep(20) // 给打印机每行一点写入时间
                    }
                    
                    // 5. 打印结尾空行，并切回普通模式
                    for (i in 1..4) {
                        stream.write(" \n".toByteArray(charset("GBK")))
                    }
                    
                    stream.write(EscPosHelper.fontSizeNormal)
                    stream.write(EscPosHelper.boldOff)
                    stream.write(EscPosHelper.initPrinter) // 最后彻底重置一下
                    stream.flush()
                    
                    runOnUiThread { updateOrderStatus("打印完成！等待下次点单...") }
                }
            } catch (e: Exception) {
                runOnUiThread { updateOrderStatus("打印出错: ${e.message}") }
            }
        }.start()
    }

    private fun showAddressInputDialog() {
        val input = EditText(this)
        input.setText(printerMacAddress)
        AlertDialog.Builder(this)
            .setTitle("打印机地址")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                printerMacAddress = input.text.toString().trim()
                autoConnectPrinter()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showStyleMenu() {
        val popup = PopupMenu(this, findViewById(R.id.btn_settings))
        popup.menu.add("实时预览: ${if (isRealtimePreviewEnabled) "ON" else "OFF"}")
        PrintStyle.values().forEach { popup.menu.add(it.description) }
        popup.setOnMenuItemClickListener { item ->
            val title = item.title.toString()
            if (title.startsWith("实时预览")) {
                isRealtimePreviewEnabled = !isRealtimePreviewEnabled
                previewOverlay.visibility = if (isRealtimePreviewEnabled) View.VISIBLE else View.GONE
                findViewById<View>(R.id.viewFinder).visibility = if (isRealtimePreviewEnabled) View.INVISIBLE else View.VISIBLE
            } else {
                currentStyle = PrintStyle.values().find { it.description == title } ?: PrintStyle.ATKINSON
            }
            true
        }
        popup.show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.viewFinder).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isRealtimePreviewEnabled) {
                            val rotation = imageProxy.imageInfo.rotationDegrees
                            val bitmap = imageProxyToBitmap(imageProxy)
                            bitmap?.let {
                                val rotated = rotateBitmap(it, rotation)
                                val scaled = Bitmap.createScaledBitmap(rotated, 384, (rotated.height * 384 / rotated.width), true)
                                val processed = EscPosHelper.processBitmap(scaled, currentStyle, true) as Bitmap
                                runOnUiThread { previewOverlay.setImageBitmap(processed) }
                            }
                        }
                        imageProxy.close()
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalyzer)
            } catch (exc: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        if (image.format == ImageFormat.JPEG) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        
        // 处理 YUV_420_888 格式，考虑 rowStride
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        
        // 复制 Y 分量
        val yRowStride = yPlane.rowStride
        val width = image.width
        val height = image.height
        
        var pos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        // 复制 UV 分量 (NV21 格式是 V 在前 U 在后交替)
        // 注意：这里为了简化处理使用了最常用的跨距逻辑
        vBuffer.get(nv21, pos, vSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun autoConnectPrinter() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        Thread {
            try {
                val device = bluetoothAdapter.getRemoteDevice(printerMacAddress)
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    bluetoothSocket?.close()
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                    bluetoothSocket?.connect()
                    outputStream = bluetoothSocket?.outputStream
                    runOnUiThread {
                        printerStatusText.text = "● 已连接: $printerMacAddress"
                        printerStatusText.setTextColor(Color.GREEN)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    printerStatusText.text = "○ 连接失败, 重试中..."
                    printerStatusText.setTextColor(Color.RED)
                }
                Thread.sleep(3000)
                autoConnectPrinter()
            }
        }.start()
    }

    private fun takePhoto() {
        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    bitmap?.let {
                        Thread {
                            try {
                                val rotated = rotateBitmap(it, rotation)
                                outputStream?.write(EscPosHelper.initPrinter)
                                outputStream?.write(EscPosHelper.setLeftMargin(120))
                                val scaled = Bitmap.createScaledBitmap(rotated, 384, (rotated.height * 384 / rotated.width), true)
                                val command = EscPosHelper.processBitmap(scaled, currentStyle) as ByteArray
                                outputStream?.write(command)
                                outputStream?.write(EscPosHelper.feedLines(5))
                                outputStream?.flush()
                            } catch (e: Exception) {}
                        }.start()
                    }
                }
                override fun onError(exc: ImageCaptureException) {}
            }
        )
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
            autoConnectPrinter()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bluetoothSocket?.close() } catch (e: Exception) {}
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
