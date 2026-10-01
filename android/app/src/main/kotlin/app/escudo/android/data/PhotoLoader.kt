package app.escudo.android.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import app.escudo.android.data.model.PhotoUpload
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns gallery images into upload-ready JPEGs: longest side ≤ 1600 px, JPEG quality 80, EXIF
 * orientation applied (ImageDecoder rotates on decode). Raw 12 MP photos took ~40 s to upload
 * over the stage network; the damage assessor does not need them. Used by the report screen and
 * the reportAccident AppFunction alike.
 */
class PhotoLoader(private val context: Context) {

  suspend fun load(uris: List<Uri>): List<PhotoUpload> = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    uris.mapIndexed { index, uri -> PhotoUpload("photo_${index + 1}.jpg", encode(uri)) }.also { photos ->
      Log.i(TAG, "Encoded ${photos.size} photo(s): ${photos.sumOf { it.bytes.size } / 1024} KB in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
  }

  /**
   * The newest [count] images in the gallery. Used when an agent says "use the photos I just
   * took" without sharing URIs. Needs READ_MEDIA_IMAGES, granted once before the talk (see APPFUNCTIONS.md).
   */
  suspend fun mostRecent(count: Int): List<Uri> = withContext(Dispatchers.IO) {
    if (!canReadGallery()) throw SecurityException("READ_MEDIA_IMAGES not granted")
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
      buildList {
        while (cursor.moveToNext() && size < count) add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
      }
    }.orEmpty()
  }

  fun canReadGallery(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

  private fun encode(uri: Uri): ByteArray {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      val longest = maxOf(info.size.width, info.size.height)
      if (longest > MAX_EDGE) {
        val scale = MAX_EDGE.toFloat() / longest
        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
      }
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return ByteArrayOutputStream().use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
      bitmap.recycle()
      out.toByteArray()
    }
  }

  private companion object {
    const val MAX_EDGE = 1600
    const val JPEG_QUALITY = 80
    const val TAG = "PhotoLoader"
  }
}
