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

    private const val HISTORY_PREFS = "public_fuel_history"
    private const val HISTORY_URI = "document_uri"

    fun linkFuelHistory(context: Context, uri: Uri, grantFlags: Int) {
        val resolver = context.contentResolver
        val flags = grantFlags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            "Document picker did not grant write access"
        }
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        require(name == "Fuel-Up-History.csv") { "Select the original Fuel-Up-History.csv" }
        // Ensure this is the expected CSV before replacing its contents.
        val header = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readLine() }
        require(header == "Fuel-Up ID,Date (UTC),Miles,Gallons,MPG,Driving Seconds,Fuel Before (%),Fuel After (%)") {
            "Selected file is not a V-Sport fuel history"
        }
        resolver.takePersistableUriPermission(uri, flags)
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(HISTORY_URI, uri.toString()).apply()
    }

    fun linkedFuelHistory(context: Context): Boolean =
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .contains(HISTORY_URI)

    private fun writeLinkedFuelHistory(context: Context, bytes: ByteArray): Uri {
        val uriText = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY_URI, null)
            ?: throw IllegalStateException("Link existing Fuel-Up-History.csv in phone Settings first")
        val uri = Uri.parse(uriText)
        require(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }) { "Fuel history document write permission lost; relink in Settings" }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot update linked fuel history")
        return uri
    }

    fun write(context: Context, folder: String, name: String, mime: String, bytes: ByteArray): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        if (name == "Fuel-Up-History.csv") return writeLinkedFuelHistory(context, bytes)
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
        // Never insert another public fuel history when MediaStore cannot see the original.
        // The private atomic CSV remains intact and can be published after access is fixed.
        if (name == "Fuel-Up-History.csv" && existing == null) {
            throw IllegalStateException("Fuel history not visible in MediaStore; duplicate creation blocked")
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
