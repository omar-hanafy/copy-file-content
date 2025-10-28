package com.github.mwguerra.copyfilecontent.filter

import com.github.mwguerra.copyfilecontent.CopyFileContentSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.Locale
import java.util.regex.Pattern

/**
 * Centralized filtering logic used by copy actions and the preview dialog.
 * The engine compiles rules once per action run and memoizes per-file results.
 */
class FilterEngine(
    private val settings: CopyFileContentSettings.State,
    private val repositoryRoot: VirtualFile?,
) {
    private val logger = Logger.getInstance(FilterEngine::class.java)
    private val verdictCache = HashMap<String, Boolean>()

    private val compiledIncludes: List<CompiledRule> = settings.includeRules
        .filter { it.enabled }
        .map { CompiledRule(it, compile(it)) }

    private val compiledExcludes: List<CompiledRule> = settings.excludeRules
        .filter { it.enabled }
        .map { CompiledRule(it, compile(it)) }

    private val directoryIncludeRules: List<CompiledRule> = compiledIncludes.filter { it.source.applyToDirectories }
    private val directoryExcludeRules: List<CompiledRule> = compiledExcludes.filter { it.source.applyToDirectories }

    fun shouldEnterDirectory(directory: VirtualFile): Boolean {
        if (!settings.matchDirectories) {
            return true
        }

        val relPath = normalizePath(directory)
        val (included, excluded, reason) = evaluateCore(directory, relPath, isDirectory = true)
        val includeEnabled = settings.useFilenameFilters
        val allowed = resolveFinalVerdict(included, excluded, includeEnabled)

        if (!allowed && reason != null) {
            logger.debug("Skipping directory due to filters: $relPath ($reason)")
        } else if (!allowed) {
            logger.debug("Skipping directory due to filters: $relPath")
        }

        return allowed
    }

    fun shouldInclude(file: VirtualFile): Pair<Boolean, String?> {
        val cached = verdictCache[file.path]
        if (cached != null) {
            return cached to null
        }

        val relPath = normalizePath(file)
        val (included, excluded, reason) = evaluateCore(file, relPath, isDirectory = file.isDirectory)
        val includeEnabled = settings.useFilenameFilters
        val finalVerdict = resolveFinalVerdict(included, excluded, includeEnabled)

        verdictCache[file.path] = finalVerdict
        if (!finalVerdict && reason != null) {
            logger.debug("Filtered out: $relPath ($reason)")
        }
        return finalVerdict to reason
    }

    private fun evaluateCore(file: VirtualFile, relPath: String, isDirectory: Boolean): Triple<Boolean, Boolean, String?> {
        if (!settings.matchHiddenFiles && isHidden(file)) {
            return Triple(false, false, "hidden")
        }

        val lowerRelPath by lazy { relPath.lowercase(Locale.getDefault()) }

        val includeEnabled = settings.useFilenameFilters
        val includeMatch = if (!includeEnabled) {
            true
        } else {
            val rules = if (isDirectory) directoryIncludeRules else compiledIncludes
            if (isDirectory && rules.isEmpty()) {
                true
            } else {
                rules.any { matches(it, file, relPath, lowerRelPath, isDirectory) }
            }
        }

        val excludeMatch = if (!settings.useExcludeRules) {
            false
        } else {
            val rules = if (isDirectory) directoryExcludeRules else compiledExcludes
            rules.any { matches(it, file, relPath, lowerRelPath, isDirectory) }
        }

        val reason = when {
            includeEnabled && !includeMatch -> "no include rule matched"
            excludeMatch -> "matched exclude rule"
            else -> null
        }
        return Triple(includeMatch, excludeMatch, reason)
    }

    private fun matches(
        rule: CompiledRule,
        file: VirtualFile,
        relPath: String,
        relPathLower: String,
        isDirectory: Boolean,
    ): Boolean {
        val src = rule.source
        if (isDirectory && !src.applyToDirectories) {
            return false
        }

        return when (val compiled = rule.compiled) {
            is CompiledPattern.Regex -> compiled.pattern.matcher(adjustCase(relPath, relPathLower, src.caseSensitive)).find()
            is CompiledPattern.GlobRegex -> compiled.pattern.matcher(adjustCase(relPath, relPathLower, src.caseSensitive)).find()
            is CompiledPattern.PathContains -> {
                val haystack = if (src.caseSensitive) relPath else relPathLower
                haystack.contains(compiled.needle)
            }
            CompiledPattern.None -> primitiveMatch(rule, file, relPath, relPathLower, isDirectory)
        }
    }

    private fun primitiveMatch(
        rule: CompiledRule,
        file: VirtualFile,
        relPath: String,
        relPathLower: String,
        isDirectory: Boolean,
    ): Boolean {
        val src = rule.source
        return when (src.type) {
            MatchType.EXTENSION -> {
                if (isDirectory) return false
                val extension = file.extension?.let { adjustCase(it, it.lowercase(Locale.getDefault()), src.caseSensitive) } ?: ""
                val pattern = adjustCase(src.pattern.removePrefix("."), src.pattern.removePrefix(".").lowercase(Locale.getDefault()), src.caseSensitive)
                extension == pattern
            }
            MatchType.FILETYPE_ID -> {
                if (isDirectory) return false
                val fileTypeName = FileTypeManager.getInstance().getFileTypeByFile(file).name
                val adjustedName = adjustCase(fileTypeName, fileTypeName.lowercase(Locale.getDefault()), src.caseSensitive)
                val pattern = adjustCase(src.pattern, src.pattern.lowercase(Locale.getDefault()), src.caseSensitive)
                adjustedName == pattern
            }
            MatchType.PATH_CONTAINS -> {
                val haystack = if (src.caseSensitive) relPath else relPathLower
                val needle = if (src.caseSensitive) src.pattern else src.pattern.lowercase(Locale.getDefault())
                haystack.contains(needle)
            }
            else -> false
        }
    }

    private fun compile(rule: FilterRule): CompiledPattern = when (rule.type) {
        MatchType.REGEX -> {
            val flags = if (rule.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            CompiledPattern.Regex(Pattern.compile(rule.pattern, flags))
        }
        MatchType.GLOB -> {
            val flags = if (rule.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
            CompiledPattern.GlobRegex(Pattern.compile(globToRegex(rule.pattern), flags))
        }
        MatchType.PATH_CONTAINS -> {
            val needle = if (rule.caseSensitive) rule.pattern else rule.pattern.lowercase(Locale.getDefault())
            CompiledPattern.PathContains(needle, rule.caseSensitive)
        }
        else -> CompiledPattern.None
    }

    private fun globToRegex(glob: String): String {
        val normalized = glob.replace('\\', '/')
        val builder = StringBuilder("^")
        var index = 0
        while (index < normalized.length) {
            when (val ch = normalized[index]) {
                '*' -> {
                    val isDouble = index + 1 < normalized.length && normalized[index + 1] == '*'
                    if (isDouble) {
                        val nextIndex = index + 2
                        val hasSlash = nextIndex < normalized.length && normalized[nextIndex] == '/'
                        if (hasSlash) {
                            builder.append("(?:.*/)?")
                            index = nextIndex
                        } else {
                            builder.append(".*")
                            index++
                        }
                    } else {
                        builder.append("[^/]*")
                    }
                }
                '?' -> builder.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> builder.append('\\').append(ch)
                '/' -> builder.append('/')
                else -> builder.append(ch)
            }
            index++
        }
        builder.append("$")
        return builder.toString()
    }

    private fun adjustCase(original: String, lower: String, caseSensitive: Boolean): String =
        if (caseSensitive) original else lower

    private fun normalizePath(file: VirtualFile): String {
        val relative = repositoryRoot?.let { root -> VfsUtil.getRelativePath(file, root, '/') }
        return (relative ?: file.path).replace('\\', '/')
    }

    private fun isHidden(file: VirtualFile): Boolean {
        if (file.name.startsWith('.')) {
            return true
        }
        return try {
            Files.isHidden(Paths.get(file.path))
        } catch (_: InvalidPathException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private fun resolveFinalVerdict(includeMatch: Boolean, excludeMatch: Boolean, includeEnabled: Boolean): Boolean {
        return when (settings.overlapPolicy) {
            OverlapPolicy.EXCLUDE_OVERRIDES -> if (includeEnabled) {
                includeMatch && !excludeMatch
            } else {
                !excludeMatch
            }

            OverlapPolicy.INCLUDE_OVERRIDES -> if (includeEnabled) {
                includeMatch || !excludeMatch
            } else {
                !excludeMatch
            }
        }
    }
}
