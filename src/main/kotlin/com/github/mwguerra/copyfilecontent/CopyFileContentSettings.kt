package com.github.mwguerra.copyfilecontent

import com.github.mwguerra.copyfilecontent.filter.FilterRule
import com.github.mwguerra.copyfilecontent.filter.MatchType
import com.github.mwguerra.copyfilecontent.filter.OverlapPolicy
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "CopyFileContentSettings",
    storages = [Storage("CopyFileContentSettings.xml")]
)
class CopyFileContentSettings : PersistentStateComponent<CopyFileContentSettings.State> {
    data class State(
        var headerFormat: String = "// file: \$FILE_PATH",
        var preText: String = "",
        var postText: String = "",
        var fileCountLimit: Int = 30,
        @Deprecated("Use includeRules")
        var filenameFilters: List<String> = listOf(),
        var addExtraLineBetweenFiles: Boolean = true,
        var setMaxFileCount: Boolean = true,
        var showCopyNotification: Boolean = true,
        var useFilenameFilters: Boolean = false,
        var strictMemoryRead: Boolean = true,
        var maxFileSizeKB: Int = 500,
        var includeRules: MutableList<FilterRule> = mutableListOf(),
        var excludeRules: MutableList<FilterRule> = mutableListOf(),
        var useExcludeRules: Boolean = false,
        var matchDirectories: Boolean = true,
        var matchHiddenFiles: Boolean = false,
        var overlapPolicy: OverlapPolicy = OverlapPolicy.EXCLUDE_OVERRIDES,
        var settingsSchemaVersion: Int = CURRENT_SCHEMA_VERSION,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        if (myState.settingsSchemaVersion >= CURRENT_SCHEMA_VERSION) {
            return
        }

        if (myState.settingsSchemaVersion < 2) {
            if (myState.includeRules.isEmpty() && myState.filenameFilters.isNotEmpty()) {
                myState.includeRules = myState.filenameFilters
                    .mapNotNull { legacy ->
                        val trimmed = legacy.trim()
                        if (trimmed.isEmpty()) {
                            null
                        } else {
                            FilterRule(
                                enabled = true,
                                type = MatchType.EXTENSION,
                                pattern = trimmed.removePrefix("."),
                                caseSensitive = false,
                                applyToDirectories = false,
                            )
                        }
                    }
                    .toMutableList()
            }
        }

        myState.settingsSchemaVersion = CURRENT_SCHEMA_VERSION
    }

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 2

        fun getInstance(project: Project): CopyFileContentSettings? =
            project.getService(CopyFileContentSettings::class.java)
    }
}
