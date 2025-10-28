package com.github.mwguerra.copyfilecontent.filter

/** Predefined sets of rules to help users bootstrap common configurations. */
object RulePresets {
    val CommonSourceFiles: List<FilterRule> = listOf(
        // File types provide stability across uppercase/lowercase extensions.
        FilterRule(true, MatchType.FILETYPE_ID, "JAVA"),
        FilterRule(true, MatchType.FILETYPE_ID, "Kotlin"),
        FilterRule(true, MatchType.FILETYPE_ID, "Groovy"),
        FilterRule(true, MatchType.FILETYPE_ID, "Scala"),
        FilterRule(true, MatchType.FILETYPE_ID, "Markdown"),
        // Common web extensions.
        FilterRule(true, MatchType.EXTENSION, "ts"),
        FilterRule(true, MatchType.EXTENSION, "tsx"),
        FilterRule(true, MatchType.EXTENSION, "js"),
        FilterRule(true, MatchType.EXTENSION, "jsx"),
        FilterRule(true, MatchType.EXTENSION, "css"),
        FilterRule(true, MatchType.EXTENSION, "scss"),
        FilterRule(true, MatchType.EXTENSION, "json"),
        FilterRule(true, MatchType.EXTENSION, "yaml"),
        FilterRule(true, MatchType.EXTENSION, "yml"),
        // Build & config.
        FilterRule(true, MatchType.GLOB, "**/*.gradle.kts"),
        FilterRule(true, MatchType.EXTENSION, "gradle"),
        FilterRule(true, MatchType.EXTENSION, "toml"),
        FilterRule(true, MatchType.EXTENSION, "properties"),
        FilterRule(true, MatchType.EXTENSION, "xml"),
        FilterRule(true, MatchType.EXTENSION, "sh"),
        FilterRule(true, MatchType.EXTENSION, "bat"),
    )

    val IgnoreBuildOutputs: List<FilterRule> = listOf(
        FilterRule(true, MatchType.PATH_CONTAINS, "/build/", false, true),
        FilterRule(true, MatchType.PATH_CONTAINS, "/out/", false, true),
        FilterRule(true, MatchType.PATH_CONTAINS, "/target/", false, true),
        FilterRule(true, MatchType.PATH_CONTAINS, "/.gradle/", false, true),
        FilterRule(true, MatchType.PATH_CONTAINS, "/.idea/", false, true),
        FilterRule(true, MatchType.PATH_CONTAINS, "/node_modules/", false, true),
        FilterRule(true, MatchType.GLOB, "**/*.min.*", false, false),
        FilterRule(true, MatchType.GLOB, "**/*.map", false, false),
    )
}
