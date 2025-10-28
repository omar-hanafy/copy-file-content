package com.github.mwguerra.copyfilecontent.filter

import java.util.regex.Pattern

/** Types of matching that can be applied to a rule. */
enum class MatchType {
    EXTENSION,
    GLOB,
    REGEX,
    FILETYPE_ID,
    PATH_CONTAINS,
}

/** Policy describing how include and exclude matches interact. */
enum class OverlapPolicy {
    EXCLUDE_OVERRIDES,
    INCLUDE_OVERRIDES,
}

/**
 * Representation of a single filtering rule. All rules are evaluated against
 * a normalized, forward-slash separated, repository-relative path.
 */
data class FilterRule(
    var enabled: Boolean = true,
    var type: MatchType = MatchType.EXTENSION,
    var pattern: String = "",
    var caseSensitive: Boolean = false,
    /** If true, allows the rule to gate directories during traversal. */
    var applyToDirectories: Boolean = false,
)

/** Lightweight compiled form of patterns to avoid repeated work. */
internal sealed class CompiledPattern {
    class Regex(val pattern: Pattern) : CompiledPattern()
    class GlobRegex(val pattern: Pattern) : CompiledPattern()
    class PathContains(val needle: String, val caseSensitive: Boolean) : CompiledPattern()
    object None : CompiledPattern()
}

internal data class CompiledRule(
    val source: FilterRule,
    val compiled: CompiledPattern,
)
