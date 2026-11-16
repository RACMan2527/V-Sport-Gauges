package com.aatorque.stats

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** App-owned public Documents files. MediaStore creates the relative directories on Android 10+. */
object PublicDocuments {
    const val ROOT = "Documents/V-Sport Gauges/"
    const val LOGS = ROOT + "Logs/"
    const val EXPORTS = ROOT + "Exports/"

    private const val PREFS = "persistent_fuel_documents"
    private val fuelNames = setOf("Fuel-Tracker.json", "Fuel-Up-History.csv")

    fun linkFuelDocument(context: Context, uri: Uri, grantFlags: Int, expectedName: String) {
        require(expectedName in fuelNames)
        val resolver = context.contentResolver
        val flags = grantFlags and (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            "Read/write document permission required"
        }
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        require(name == expectedName) { "Select $expectedName" }
        val contents = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read selected document")
        if (expectedName.endsWith(".json")) {
            val obj = org.json.JSONObject(String(contents, Charsets.UTF_8))
            require(obj.has("miles") && obj.has("seconds") && obj.optJSONArray("completed") != null) {
                "Invalid fuel tracker JSON"
            }
        } else {
            require(String(contents, Charsets.UTF_8).startsWith("Fuel-Up ID,Date (UTC),Miles,")) {
                "Invalid fuel history CSV"
            }
        }
        resolver.takePersistableUriPermission(uri, flags)
        // Preserve a validated private recovery journal immediately when the user links
        // an existing tank. Never overwrite an already-present private journal.
        if (expectedName == "Fuel-Tracker.json") {
            val privateFile = File(context.filesDir, expectedName)
            if (!privateFile.exists()) {
                val journal = AtomicFile(privateFile)
                val stream = journal.startWrite()
                try {
                    stream.write(contents)
                    journal.finishWrite(stream)
                    DiagnosticLog.event("FUEL_TRACKER_RECOVERED", "linked_document_to_private bytes=" + contents.size)
                } catch (e: Exception) {
                    journal.failWrite(stream)
                    throw e
                }
            }
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(expectedName, uri.toString()).commit()) {
            "Cannot save persistent fuel document link"
        }
    }

    fun linkedFuelDocument(context: Context, name: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) != null

    /** Read-only startup validation; never truncate or replace user files. */
    fun fuelAccessError(context: Context, name: String): String? {
        require(name in fuelNames)
        val uriText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null) ?: return "link_missing"
        return try {
            val uri = Uri.parse(uriText)
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                ?: return "permission_missing"
            if (!permission.isReadPermission || !permission.isWritePermission) return "read_write_permission_missing"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return "read_failed"
            if (name == "Fuel-Tracker.json") {
                val obj = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                if (!obj.has("miles") || !obj.has("seconds") || obj.optJSONArray("completed") == null)
                    return "invalid_journal"
            } else if (!String(bytes, Charsets.UTF_8).startsWith("Fuel-Up ID,Date (UTC),Miles,")) {
                return "invalid_history"
            }
            // Query provider for write capability without modifying existing data.
            val writable = context.contentResolver.query(uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_FLAGS),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val idx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_FLAGS)
                    if (idx < 0) null else cursor.getInt(idx)
                }
            }
            if (writable != null &&
                writable and android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0)
                return "document_not_writable"
            null
        } catch (e: Exception) {
            e.javaClass.simpleName + " " + (e.message ?: "")
        }
    }

    fun initialFuelDocument(name: String): ByteArray {
        require(name in fuelNames)
        return if (name == "Fuel-Tracker.json") {
            org.json.JSONObject().put("schema", 2).put("miles", 0.0)
                .put("gallons", 0.0).put("seconds", 0L)
                .put("fuelRateValid", true).put("fuelRateSeen", false)
                .put("completed", org.json.JSONArray()).toString(2).toByteArray(Charsets.UTF_8)
        } else {
            "Fuel-Up ID,Date (UTC),Miles,Gallons,MPG,Driving Seconds,Fuel Before (%),Fuel After (%)\r\n"
                .toByteArray(Charsets.UTF_8)
        }
    }

    fun readFuelDocument(context: Context, name: String): ByteArray? {
        require(name in fuelNames)
        val uriText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null) ?: return null
        val uri = Uri.parse(uriText)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read linked $name")
    }

    private fun writeFuelDocument(context: Context, name: String, bytes: ByteArray): Uri {
        val uriText = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(name, null)
            ?: throw IllegalStateException("Link $name in phone Settings before writing")
        val uri = Uri.parse(uriText)
        require(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }) { "Write permission lost for $name; relink in Settings" }
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot write $name")
        return uri
    }

    fun write(context: Context, folder: String, name: String, mime: String, bytes: ByteArray): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        if (name in fuelNames) return writeFuelDocument(context, name, bytes)
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
