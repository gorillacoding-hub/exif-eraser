/*
 * Synthetic metadata test helper.
 * Writes a COPY of an image and explicitly labels the metadata as synthetic.
 */
package com.none.tom.exiferaser.synthetic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.random.Random

object SyntheticMetadataWriter {
    /**
     * Creates a separate copy in Pictures/ExifEraserSynthetic.
     * Supported input formats: JPEG, PNG and WebP.
     * The source URI is never modified.
     */
    suspend fun createSyntheticCopy(context: Context, sourceUri: Uri): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val temp = File.createTempFile("synthetic_exif_", ".image", context.cacheDir)
            var outputUri: Uri? = null

            try {
                val mime = resolver.getType(sourceUri)
                    ?: throw IOException("Could not determine the image type.")
                if (mime !in setOf("image/jpeg", "image/png", "image/webp")) {
                    throw IOException("Choose a JPEG, PNG, or WebP image.")
                }

                resolver.openInputStream(sourceUri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Could not open the selected image.")

                val exif = ExifInterface(temp.absolutePath)
                val latitude = Random.nextDouble(-80.0, 80.0)
                val longitude = Random.nextDouble(-180.0, 180.0)
                val latRef = if (latitude < 0) "S" else "N"
                val lonRef = if (longitude < 0) "W" else "E"

                exif.setAttribute(
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    "SYNTHETIC TEST DATA — NOT ORIGINAL CAMERA METADATA"
                )
                exif.setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "SYNTHETIC TEST DATA — generated for testing; not real provenance"
                )
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ExifEraser Synthetic Metadata Test Mode")
                exif.setAttribute(ExifInterface.TAG_MAKE, "SYNTHETIC TEST DEVICE")
                exif.setAttribute(ExifInterface.TAG_MODEL, "Example Phone / Camera (Synthetic)")
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, toDms(Math.abs(latitude)))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, latRef)
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, toDms(Math.abs(longitude)))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "SYNTHETIC")
                exif.saveAttributes()

                val values = ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        "SYNTHETIC_TEST_${System.currentTimeMillis()}.${extensionFor(mime)}"
                    )
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            "${Environment.DIRECTORY_PICTURES}/ExifEraserSynthetic"
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                outputUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IOException("Could not create the output image.")

                resolver.openOutputStream(outputUri!!, "w")?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Could not write the output image.")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        outputUri!!,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        },
                        null,
                        null
                    )
                }
                outputUri!!
            } catch (error: Exception) {
                outputUri?.let { resolver.delete(it, null, null) }
                throw error
            } finally {
                temp.delete()
            }
        }

    private fun extensionFor(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "img"
    }

    // EXIF GPS coordinates use degrees/minutes/seconds in rational form.
    private fun toDms(value: Double): String {
        val degrees = value.toInt()
        val minutesFull = (value - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60.0
        return String.format(
            Locale.US,
            "%d/1,%d/1,%d/1000",
            degrees,
            minutes,
            (seconds * 1000).toInt()
        )
    }
}