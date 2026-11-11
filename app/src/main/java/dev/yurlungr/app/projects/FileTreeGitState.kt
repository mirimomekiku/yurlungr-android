package dev.yurlungr.app.projects

internal enum class FileTreeFilter(val label: String) {
    All("All"), NotIgnored("Source"), Ignored("Ignored"), Changed("Changed"),
}

/** Git outputs already account for nested ignore rules, exceptions and tracked ignored-pattern files. */
internal class FileTreeGitState(ignoredPaths: Set<String>, changes: List<GitChange>) {
    private val ignoredFiles = ignoredPaths.filterNot { it.endsWith('/') }.toSet()
    private val ignoredDirectories = ignoredPaths.filter { it.endsWith('/') }.map { it.trimEnd('/') }.toSet()
    private val ignoredParents = ignoredPaths.flatMap { parents(it.trimEnd('/')).toList() }.toSet()
    val changesByPath = changes.associateBy { it.path.trimEnd('/') }
    private val changedParents = changes.flatMap { parents(it.path.trimEnd('/')).toList() }.toSet()
    /** How many changed files each folder holds, at any depth. */
    val changedInside: Map<String, Int> = changes.flatMap { parents(it.path.trimEnd('/')).toList() }.groupingBy { it }.eachCount()

    fun isIgnored(path: String): Boolean = path in ignoredFiles || path in ignoredDirectories || parents(path).any { it in ignoredDirectories }
    fun isChanged(path: String, directory: Boolean): Boolean = path in changesByPath || (directory && path in changedParents)

    fun includes(path: String, directory: Boolean, filter: FileTreeFilter): Boolean = when (filter) {
        FileTreeFilter.All -> true
        FileTreeFilter.NotIgnored -> !isIgnored(path)
        FileTreeFilter.Ignored -> isIgnored(path) || (directory && path in ignoredParents)
        FileTreeFilter.Changed -> isChanged(path, directory)
    }

    companion object {
        fun parseIgnoredPaths(output: String): Set<String> = output.removeSuffix("\n").split('\u0000').filter { it.isNotEmpty() }.toSet()
        private fun parents(path: String): Sequence<String> = generateSequence(path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }) {
            it.substringBeforeLast('/', "").takeIf { parent -> parent.isNotEmpty() }
        }
    }
}
