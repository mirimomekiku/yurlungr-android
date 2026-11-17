package dev.yurlungr.app.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saving copies of files out of the app (to Downloads, another folder, an SD card or a cloud drive) through
 * the system's own pickers. The user chooses where; the app gets write access to that one document or
 * folder only, so no storage permission is involved.
 */
object FileExport {
    /** Copies outlive the screen that started them (a sheet closed mid-copy still finishes). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /**
     * A name per file for saving them side by side: files from different folders that share a name get
     * " (2)", " (3)"… before the extension, so none overwrites another.
     */
    fun uniqueNames(files: List<File>): List<Pair<File, String>> {
        val taken = HashSet<String>()
        return files.map { f ->
            // A leading dot is part of the name (.env), not an extension.
            val dot = f.name.lastIndexOf('.').takeIf { it > 0 }
            val base = if (dot == null) f.name else f.name.substring(0, dot)
            val ext = if (dot == null) "" else f.name.substring(dot)
            var name = f.name
            var n = 2
            while (!taken.add(name.lowercase())) name = "$base ($n)$ext".also { n++ }
            f to name
        }
    }

    private fun copy(context: Context, source: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("the folder can't be written to")
        out.use { o -> source.inputStream().use { it.copyTo(o, 64 * 1024) } }
    }

    /** Copies [source] into the document the user created with the picker. */
    fun saveTo(context: Context, source: File, target: Uri) {
        val app = context.applicationContext
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { check(source.isFile) { "${source.name} is no longer on disk" }; copy(app, source, target) }
            }
            if (result.isFailure) runCatching { withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(app.contentResolver, target) } }
            Toast.makeText(app, result.fold({ "Saved ${source.name}" }, { "Couldn't save ${source.name}: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    /** Copies every file in [files] into the folder the user picked ([tree]); the provider renames on clashes. */
    fun saveAllTo(context: Context, files: List<File>, tree: Uri) {
        val app = context.applicationContext
        scope.launch {
            val failed = withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                uniqueNames(files.filter { it.isFile }).mapNotNull { (file, name) ->
                    runCatching {
                        val doc = DocumentsContract.createDocument(resolver, folder, mimeOf(name), name) ?: error("couldn't create it")
                        runCatching { copy(app, file, doc) }.onFailure { runCatching { DocumentsContract.deleteDocument(resolver, doc) } }.getOrThrow()
                    }.exceptionOrNull()?.let { file.name }
                } + files.filter { !it.isFile }.map { it.name }
            }
            val saved = files.size - failed.size
            val message = when {
                failed.isEmpty() -> "Saved $saved file${if (saved == 1) "" else "s"}"
                saved == 0 -> "Couldn't save the files to that folder"
                else -> "Saved $saved of ${files.size} files. Not saved: ${failed.take(3).joinToString()}${if (failed.size > 3) "…" else ""}"
            }
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
        }
    }

    /** ACTION_CREATE_DOCUMENT with the file's own name and type, so the picker suggests both. */
    private class CreateDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    /** A function that asks where to save a copy of a file (the system "Save to…" picker), then saves it. */
    @Composable
    fun rememberSaveFile(): (File) -> Unit {
        val context = LocalContext.current
        // Survives the activity being recreated while the picker is open.
        var pending by rememberSaveable { mutableStateOf<String?>(null) }
        val launcher = rememberLauncherForActivityResult(CreateDocument()) { uri ->
            val path = pending
            pending = null
            if (uri != null && path != null) saveTo(context, File(path), uri)
        }
        return { file ->
            pending = file.path
            runCatching { launcher.launch(file.name to mimeOf(file.name)) }
                .onFailure { pending = null; Toast.makeText(context, "No app here can save files", Toast.LENGTH_LONG).show() }
        }
    }

    /** A function that asks for a folder (the system folder picker), then saves copies of all the files into it. */
    @Composable
    fun rememberSaveAll(): (List<File>) -> Unit {
        val context = LocalContext.current
        var pending by rememberSaveable { mutableStateOf<List<String>?>(null) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            val paths = pending
            pending = null
            if (tree != null && paths != null) saveAllTo(context, paths.map(::File), tree)
        }
        return { files ->
            pending = files.map { it.path }
            runCatching { launcher.launch(null) }
                .onFailure { pending = null; Toast.makeText(context, "No app here can pick a folder", Toast.LENGTH_LONG).show() }
        }
    }
}
