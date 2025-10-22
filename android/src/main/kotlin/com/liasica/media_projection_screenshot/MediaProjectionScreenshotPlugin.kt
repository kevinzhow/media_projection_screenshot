package com.liasica.media_projection_screenshot

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import androidx.annotation.RequiresApi
import im.zego.media_projection_creator.MediaProjectionCreatorCallback
import im.zego.media_projection_creator.RequestMediaProjectionPermissionManager
import io.flutter.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log


/** MediaProjectionScreenshotPlugin */
class MediaProjectionScreenshotPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var methodChannel: MethodChannel
  private lateinit var context: Context
  private var events: EventChannel.EventSink? = null

  private var mediaProjection: MediaProjection? = null

  private var mVirtualDisplay: VirtualDisplay? = null
  private var mImageReader: ImageReader? = null

  private var isLiving: AtomicBoolean = AtomicBoolean(false)
  private var processingTime = AtomicLong(System.currentTimeMillis())
  private var counting = AtomicLong(0)

  private var mWidth = 0
  private var mHeight = 0

  private var imageHandlerThread: HandlerThread? = null
  private var imageHandler: Handler? = null

  var defaultRotattion = -1

  companion object {
    const val LOG_TAG = "MP_SCREENSHOT"
    const val CAPTURE_SINGLE = "MP_CAPTURE_SINGLE"
    const val CAPTURE_CONTINUOUS = "MP_CAPTURE_CONTINUOUS"
    const val METHOD_CHANNEL_NAME = "com.liasica.media_projection_screenshot/method"
    const val EVENT_CHANNEL_NAME = "com.liasica.media_projection_screenshot/event"
    const val FPS = 15
  }

  @RequiresApi(Build.VERSION_CODES.O)
  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, METHOD_CHANNEL_NAME)
    methodChannel.setMethodCallHandler(this)

    EventChannel(flutterPluginBinding.binaryMessenger, EVENT_CHANNEL_NAME).setStreamHandler(this)

    context = flutterPluginBinding.applicationContext

    imageHandlerThread = HandlerThread("ImageProcessingThread").apply {
      start()
      imageHandler = Handler(looper)
      Log.i(LOG_TAG, "ImageProcessingThread started for FIFO image handling.")
    }

    RequestMediaProjectionPermissionManager.getInstance().setRequestPermissionCallback(mediaProjectionCreatorCallback)
  }

  @RequiresApi(Build.VERSION_CODES.O)
  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "takeCapture" -> {
        takeCapture(call, result)
      }

      "startCapture" -> {
        startCapture(call, result)
      }

      "stopCapture" -> {
        stopCapture(result)
      }

      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methodChannel.setMethodCallHandler(null)
    imageHandlerThread?.quitSafely()
    imageHandlerThread = null
    imageHandler = null
  }


  override fun onListen(arguments: Any?, es: EventChannel.EventSink?) {
    events = es
  }

  override fun onCancel(arguments: Any?) {}

  private val mediaProjectionCreatorCallback = MediaProjectionCreatorCallback { projection, errorCode ->
    when (errorCode) {
      RequestMediaProjectionPermissionManager.ERROR_CODE_SUCCEED -> {
        Log.i(LOG_TAG, "Create media projection succeeded!")
        mediaProjection = projection
        initVirtualDisplay(mediaProjection!!);
      }

      RequestMediaProjectionPermissionManager.ERROR_CODE_FAILED_USER_CANCELED -> {
        Log.e(LOG_TAG, "Create media projection failed because can not get permission")
      }

      RequestMediaProjectionPermissionManager.ERROR_CODE_FAILED_SYSTEM_VERSION_TOO_LOW -> {
        Log.e(LOG_TAG, "Create media projection failed because system api level is lower than 21")
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun initVirtualDisplay(mediaProjection: MediaProjection) {
    mediaProjection.registerCallback(mediaProjectionCallback, null)
    val metrics = Resources.getSystem().displayMetrics
    mWidth = metrics.widthPixels
    mHeight = metrics.heightPixels

    val mDensity = Resources.getSystem().displayMetrics.densityDpi

    if (mImageReader == null) {
      mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2)
    }

    mVirtualDisplay = mediaProjection.createVirtualDisplay(
      CAPTURE_CONTINUOUS,
      mWidth,
      mHeight,
      mDensity,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
      mImageReader!!.surface,
      null,
      null,
    )

    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    defaultRotattion = displayManager.displays.first().rotation
    // 监听旋转
    displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {
        Log.i( LOG_TAG, "Display added: $displayId")
      }
      override fun onDisplayRemoved(displayId: Int) {
        Log.i( LOG_TAG, "Display removed: $displayId")
      }

      override fun onDisplayChanged(displayId: Int) {
        val display = displayManager.getDisplay(displayId) ?: return
        val newRotation = display.rotation
        if (displayId == Display.DEFAULT_DISPLAY && newRotation != defaultRotattion) {
          Log.i(LOG_TAG, "Display rotation changed: $defaultRotattion -> $newRotation, recreating VirtualDisplay")
          defaultRotattion = newRotation

          val metrics = getCurrentDisplayMetrics()

          // 1) 先创建新的 ImageReader
          val newReader = ImageReader.newInstance(metrics.w, metrics.h, PixelFormat.RGBA_8888, 3)
          // 2) 切换 Surface（先 setSurface，再 resize，更平滑）
          mVirtualDisplay?.setSurface(newReader.surface)
          // 3) 调整 VirtualDisplay 尺寸
          mVirtualDisplay?.resize(metrics.w, metrics.h, metrics.density)

          // 4) 关闭旧 reader，替换引用，重绑回调
          mImageReader?.setOnImageAvailableListener(null, null)
          mImageReader?.close()
          mImageReader = newReader

        }
      }
    }, imageHandler)

  }

  private data class Metrics(val w: Int, val h: Int, val density: Int)

  private fun getCurrentDisplayMetrics(): Metrics {
    val density = Resources.getSystem().displayMetrics.densityDpi

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
      val b = wm.currentWindowMetrics.bounds
      Metrics(b.width(), b.height(), density)
    } else {
      val dm = Resources.getSystem().displayMetrics
      Metrics(dm.widthPixels, dm.heightPixels, density)
    }
  }

  private fun stopCapture(result: Result) {
//    if (!isLiving.compareAndSet(true, false)) {
//      Log.i(LOG_TAG, "Screen capture is not start")
//      result.success(true)
//      return
//    }

    mVirtualDisplay?.release()
    mVirtualDisplay = null

    mImageReader?.surface?.release()
    mImageReader?.close()
    mImageReader = null

    mediaProjection?.stop()

    Log.i(LOG_TAG, "Screen capture stopped")
    result.success(true)
  }

  val mediaProjectionCallback = object : MediaProjection.Callback() {
    override fun onStop() {
      // Handle the stopping of the projection, e.g., release resources
      // virtualDisplay.release()
      // mediaProjection.unregisterCallback(this)
    }
  }

  @SuppressLint("WrongConstant")
  @RequiresApi(Build.VERSION_CODES.O)
  private fun startCapture(call: MethodCall, result: Result) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      result.error(LOG_TAG, "Create media projection failed because system api level is lower than 21", null)
      return
    }

    if (mediaProjection == null) {
      result.error(LOG_TAG, "Must request permission before take capture", null)
      return
    }

    if (!this.isLiving.compareAndSet(false, true)) {
      result.error(LOG_TAG, "Screen capture has started", null)
      return
    }

    val metrics = Resources.getSystem().displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels

    val region = call.arguments as Map<*, *>?
    val fps = region?.let {
      region["fps"] as Int?
    } ?: FPS
    Log.i(LOG_TAG, "Screen capture started: FPS = $fps")

    mImageReader!!.setOnImageAvailableListener({ reader ->
      val image = reader.acquireLatestImage()
      val start = System.currentTimeMillis()

      val planes = image.planes
      val buffer = planes[0].buffer
      val pixelStride = planes[0].pixelStride
      val rowStride = planes[0].rowStride
      val rowPadding = rowStride - pixelStride * width
      val padding = rowPadding / pixelStride

      var bitmap = Bitmap.createBitmap(width + padding, height, Bitmap.Config.ARGB_8888)
      bitmap.copyPixelsFromBuffer(buffer)

      image.close()

      // 控制速率
      if (fps == 0 || System.currentTimeMillis() - processingTime.get() >= 1000 / fps) {
        processingTime.set(System.currentTimeMillis())
        region?.let { params ->
          val x = params["x"] as Int?
          val y = params["y"] as Int?
          val w = params["width"] as Int?
          val h = params["height"] as Int?
          if (x != null && y != null && w != null && h != null) {
            bitmap = bitmap.crop(x + padding / 2, y, w, h)
          }
        }

        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)

        // val byteArray = outputStream.toByteArray()
        // val b64 = "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.NO_WRAP)

        // val file = File("${context.cacheDir.absolutePath}/${System.currentTimeMillis()}.jpeg")
        // val fOut = file.outputStream()
        // bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fOut)
        // fOut.flush()
        // fOut.close()

        val byteArray = outputStream.toByteArray()
        val queue = counting.addAndGet(1)
        events?.success(
          mapOf(
            "bytes" to byteArray,
            "width" to bitmap.width,
            "height" to bitmap.height,
            "rowBytes" to bitmap.rowBytes,
            "format" to Bitmap.Config.ARGB_8888.toString(),
            "pixelStride" to pixelStride,
            "rowStride" to rowStride,
            "nv21" to getYV12(bitmap.width, bitmap.height, bitmap),
            "time" to System.currentTimeMillis(),
            "queue" to queue,
          )
        )

        val ts = System.currentTimeMillis() - start
        Log.i(LOG_TAG, "n = \t${queue}, ts = $ts\t, outputStream.size = ${outputStream.size()}")
      }
    }, null)

    result.success(true)
  }

  @SuppressLint("WrongConstant")
  private fun takeCapture(call: MethodCall, result: Result) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      result.error(LOG_TAG, "Create media projection failed because system api level is lower than 21", null)
      return
    }

    if (mediaProjection == null) {
      result.error(LOG_TAG, "Must request permission before take capture", null)
      return
    }

    imageHandler?.post {
      val image = mImageReader!!.acquireLatestImage()
      // Avoid black screen for the first capture

      val planes = image.planes
      val buffer = planes[0].buffer
      val pixelStride = planes[0].pixelStride
      val rowStride = planes[0].rowStride
      val rowPadding = rowStride - pixelStride * mWidth
      val padding = rowPadding / pixelStride

      val bitmap = imageToBitmapRgba(image)
      val outputStream = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
      val byteArray = outputStream.toByteArray()

      image.close()

      val finalMap = mapOf(
        "bytes" to byteArray,
        "width" to bitmap.width,
        "height" to bitmap.height,
        "rowBytes" to bitmap.rowBytes,
        "format" to Bitmap.Config.ARGB_8888.toString(),
        "pixelStride" to pixelStride,
        "rowStride" to rowStride,
        "nv21" to getYV12(bitmap.width, bitmap.height, bitmap),
        "time" to System.currentTimeMillis(),
        "queue" to 1
      )

      Handler(Looper.getMainLooper()).post({
        result.success(
          finalMap
        )
      })
    }
  }

  private fun Bitmap.crop(x: Int, y: Int, width: Int, height: Int): Bitmap {
    return Bitmap.createBitmap(this, x, y, width, height, null, true)
  }

  private fun getYV12(inputWidth: Int, inputHeight: Int, scaled: Bitmap): ByteArray {
    val argb = IntArray(inputWidth * inputHeight)
    scaled.getPixels(argb, 0, inputWidth, 0, 0, inputWidth, inputHeight)
    val yuv = ByteArray(inputWidth * inputHeight * 3 / 2)
    encodeYV12(yuv, argb, inputWidth, inputHeight)
    scaled.recycle()
    return yuv
  }

  private fun imageToBitmapRgba(image: Image): Bitmap {
    // 仅适用于 RGBA_8888
    val crop = image.cropRect
    val width = crop.width()
    val height = crop.height()
    val plane = image.planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride // 通常 4
    val rowStride = plane.rowStride

    // 目标是紧凑的 ARGB_8888
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val dst = ByteBuffer.allocate(width * height * pixelStride)

    val rowBytes = width * pixelStride
    val row = ByteArray(rowBytes)

    // 逐行读取有效区域：从 crop.left 开始，跳过 padding
    for (y in 0 until height) {
      val srcPos = (y + crop.top) * rowStride + crop.left * pixelStride
      buffer.position(srcPos)
      buffer.get(row, 0, rowBytes)
      dst.put(row)
    }

    dst.rewind()
    out.copyPixelsFromBuffer(dst)
    return out
  }

  private fun encodeYV12(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
    val frameSize = width * height
    var yIndex = 0
    var uIndex = frameSize
    var vIndex = frameSize + frameSize / 4
    // var a: Int
    var r: Int
    var g: Int
    var b: Int
    var y: Int
    var u: Int
    var v: Int
    var index = 0
    for (j in 0 until height) {
      for (i in 0 until width) {
        // a = argb[index] and -0x1000000 shr 24 // a is not used obviously
        r = argb[index] and 0xff0000 shr 16
        g = argb[index] and 0xff00 shr 8
        b = argb[index] and 0xff shr 0

        // well known RGB to YUV algorithm
        y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
        u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
        v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

        // YV12 has a plane of Y and two chroma plans (U, V) planes each sampled by a factor of 2
        //    meaning for every 4 Y pixels there are 1 V and 1 U.  Note the sampling is every other
        //    pixel AND every other scanline.
        yuv420sp[yIndex++] = y.toByte()
        if (j % 2 == 0 && index % 2 == 0) {
          yuv420sp[uIndex++] = v.toByte()
          yuv420sp[vIndex++] = u.toByte()
        }
        index++
      }
    }
  }
}
