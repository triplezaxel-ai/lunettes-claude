package com.keonyrus.tupovision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ExifInterface
import com.meta.wearable.dat.camera.types.VideoFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Holds the newest uncompressed video frame from the glasses and turns frames and photos into JPEG
 * for the model.
 */
object Frames {
  private const val JPEG_QUALITY = 82
  // Long side sent to the model. Bigger costs tokens and upload time without helping much.
  private const val MAX_SIDE = 1280

  private val lock = Any()
  private var data: ByteArray? = null
  private var length = 0
  private var width = 0
  private var height = 0

  @Volatile
  var count: Long = 0
    private set

  @Volatile
  var info: String = "no frames yet"
    private set

  fun reset() {
    synchronized(lock) {
      length = 0
      width = 0
      height = 0
    }
    count = 0
    info = "no frames yet"
  }

  /** Copies the frame out of the SDK's buffer, which is only valid during the callback. */
  fun offer(frame: VideoFrame) {
    if (frame.isCompressed) return
    val buffer = frame.buffer
    val size = buffer.remaining()
    if (size <= 0) return
    synchronized(lock) {
      var target = data
      if (target == null || target.size < size) {
        target = ByteArray(size)
        data = target
      }
      val position = buffer.position()
      buffer.get(target, 0, size)
      buffer.position(position)
      length = size
      width = frame.width
      height = frame.height
    }
    count += 1
    info = "${frame.width}x${frame.height}, $size bytes"
  }

  /**
   * The newest video frame as JPEG, or null when there is none or its layout is not the planar
   * YUV 4:2:0 this code understands (then the caller falls back to a photo).
   */
  fun latestJpeg(quality: Int = JPEG_QUALITY): ByteArray? {
    var copy: ByteArray? = null
    var w = 0
    var h = 0
    synchronized(lock) {
      val source = data
      val evenSize = width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0
      if (source != null && evenSize && length == width * height * 3 / 2) {
        w = width
        h = height
        copy = source.copyOf(length)
      }
    }
    val bytes = copy ?: return null
    return i420ToJpeg(bytes, w, h, quality)
  }

  private fun i420ToJpeg(i420: ByteArray, w: Int, h: Int, quality: Int): ByteArray? {
    val ySize = w * h
    val chromaSize = ySize / 4
    // YuvImage wants NV21: the Y plane, then V and U interleaved.
    val nv21 = ByteArray(ySize + 2 * chromaSize)
    System.arraycopy(i420, 0, nv21, 0, ySize)
    var out = ySize
    val uStart = ySize
    val vStart = ySize + chromaSize
    for (i in 0 until chromaSize) {
      nv21[out++] = i420[vStart + i]
      nv21[out++] = i420[uStart + i]
    }
    return try {
      val stream = ByteArrayOutputStream()
      YuvImage(nv21, ImageFormat.NV21, w, h, null)
          .compressToJpeg(Rect(0, 0, w, h), quality, stream)
      stream.toByteArray()
    } catch (e: Exception) {
      null
    }
  }

  fun bitmapToJpeg(bitmap: Bitmap): ByteArray? =
      try {
        val scaled = scaleDown(bitmap)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        stream.toByteArray()
      } catch (e: Exception) {
        null
      }

  /** Photos arrive as HEIC with the orientation in EXIF, which the decoder does not apply. */
  fun heicToJpeg(buffer: ByteBuffer): ByteArray? =
      try {
        val source = buffer.duplicate().apply { rewind() }
        val bytes = ByteArray(source.remaining())
        source.get(bytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (decoded == null) null else bitmapToJpeg(rotate(decoded, exifRotation(bytes)))
      } catch (e: Exception) {
        null
      } catch (e: OutOfMemoryError) {
        null
      }

  /** A small bitmap of what was sent, for the on-screen check. */
  fun preview(jpeg: ByteArray): Bitmap? =
      try {
        BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = 2 },
        )
      } catch (e: Exception) {
        null
      }

  private fun scaleDown(bitmap: Bitmap): Bitmap {
    val longSide = maxOf(bitmap.width, bitmap.height)
    if (longSide <= MAX_SIDE) return bitmap
    val factor = MAX_SIDE.toFloat() / longSide
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * factor).toInt().coerceAtLeast(1),
        (bitmap.height * factor).toInt().coerceAtLeast(1),
        true,
    )
  }

  private fun exifRotation(bytes: ByteArray): Int =
      try {
        val exif = ByteArrayInputStream(bytes).use { ExifInterface(it) }
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90
          ExifInterface.ORIENTATION_ROTATE_180 -> 180
          ExifInterface.ORIENTATION_ROTATE_270 -> 270
          else -> 0
        }
      } catch (e: Exception) {
        0
      }

  private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }
}
