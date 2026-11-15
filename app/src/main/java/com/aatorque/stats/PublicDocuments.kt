package com.aatorque.stats

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** App-owned public Documents files. MediaStore creates the relative directories on Android 10+. */
object PublicDocuments {
    const val ROOT = "Documents/V-Sport Gauges/"
    const val LOGS = ROOT + "Logs/"
    const val EXPORTS = ROOT + "Exports/"

    fun write(context: Context, folder: String, name: String, mime: String, bytes: ByteArray): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var existing: Uri? = null
        resolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME}=? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH}=?",
            arrayOf(name, folder), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                existing = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
            put(MediaStore.Files.FileColumns.MIME_TYPE, mime)
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, folder)
        }) ?: throw IllegalStateException("Cannot create public Documents file: $name")
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot write public Documents file: $name")
        return uri
    }
}
