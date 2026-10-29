package com.danemadsen.curie.data

import java.io.File

/**
 * True when [name] is a file name that cannot leave its directory: no separator, not "." or "..".
 * The data layer stores its files by name, relative to their directory, so they still resolve
 * when the app's files move (a device transfer, another user profile, adopted storage).
 */
internal fun isPlainFileName(name: String): Boolean {
    val isSpecial = name.isEmpty() || name == "." || name == ".."
    return !isSpecial && File(name).name == name
}

/** Deletes the file [name] in [directory]; a name that could leave the directory is ignored. */
internal fun deleteFileNamed(directory: File, name: String) {
    if (isPlainFileName(name)) {
        File(directory, name).delete()
    }
}
