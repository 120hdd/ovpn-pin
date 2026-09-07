package com.erelay.relay

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Configs, in from a folder somebody picked.
 *
 * The desktop's chooseFolder points the app at a folder and reads it where it
 * stands. A phone cannot: a picked folder arrives as a content URI rather
 * than a path, nothing outside the picker can open it by name, and the grant
 * does not survive much. So the files are copied in rather than read where
 * they are, and the app keeps reading its own folder - which is the only one
 * the Go core can be handed a path to.
 *
 * This is what makes the phone usable without a cable. Before it, configs
 * arrived only by `adb push`, which means a computer, a cable and a developer
 * setting, to move files a file manager already has.
 */
object Import {

    /** What came of one import, in the shape chooseFolder answers with. */
    data class Result(val copied: Int, val skipped: Int, val seen: Int)

    /**
     * Copy every .ovpn in a picked tree into the app's own folder.
     *
     * One cursor over the tree rather than DocumentFile.listFiles(), which is
     * one binder round trip per file and takes seconds over a folder of four
     * hundred.
     *
     * Existing names are skipped rather than overwritten. A config is named
     * for the exit and the address it was pinned to, so the same name is the
     * same file - and re-importing a folder should be free rather than four
     * hundred writes.
     *
     * Windscribe's and Surfshark's unpinned configs are told apart by the
     * mark in the name and sent to their own inboxes, so that a folder holding
     * both arrives as two piles ready to pin rather than one that neither
     * provider card can count.
     */
    fun fromTree(ctx: Context, tree: Uri): Result {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree))

        var copied = 0
        var skipped = 0
        var seen = 0

        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getString(0) ?: continue
                val name = rows.getString(1) ?: continue
                if (!name.endsWith(".ovpn", ignoreCase = true)) continue
                seen++

                val into = File(destination(ctx, name), name)
                if (into.exists()) {
                    skipped++
                    continue
                }
                val from = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                try {
                    into.parentFile?.mkdirs()
                    ctx.contentResolver.openInputStream(from)?.use { input ->
                        into.outputStream().use { input.copyTo(it) }
                    } ?: continue
                    copied++
                } catch (e: Exception) {
                    // One unreadable file is not a failed import. The count
                    // says how many arrived, and the page prints the count.
                    into.delete()
                }
            }
        }
        return Result(copied, skipped, seen)
    }

    /**
     * Which folder a config belongs in, decided by its own name.
     *
     * A pinned config carries an address on the end and goes straight into
     * the folder the app races from. An unpinned one goes to its provider's
     * inbox, where the pin run will find it - putting it in pinned/ would
     * make the catalogue try to dial a hostname, which is the one thing this
     * whole repo exists to stop doing.
     */
    private fun destination(ctx: Context, name: String): File = when {
        PINNED.containsMatchIn(name) -> AppFiles.pinned(ctx)
        name.contains(".ws.") -> AppFiles.windscribe(ctx)
        else -> AppFiles.configs(ctx)
    }

    /** The address a pin run puts on the end: `..._146.70.253.194.ovpn`. */
    private val PINNED = Regex("""_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$""")
}
