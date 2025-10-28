package com.github.mwguerra.copyfilecontent

import com.github.mwguerra.copyfilecontent.filter.OverlapPolicy
import com.github.mwguerra.copyfilecontent.filter.RulePresets
import com.github.mwguerra.copyfilecontent.ui.FilterPreviewDialog
import com.github.mwguerra.copyfilecontent.ui.RuleTable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class CopyFileContentConfigurable(private val project: Project) : Configurable {

    private var settings: CopyFileContentSettings? = null

    // ---------- Basic editors ----------
    private val headerFormatArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(JBUI.Borders.empty(5), RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1), true)
        lineWrap = true
        wrapStyleWord = true
    }
    private val preTextArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(JBUI.Borders.empty(5), RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1), true)
        lineWrap = true
        wrapStyleWord = true
    }
    private val postTextArea = JBTextArea(4, 20).apply {
        border = JBUI.Borders.merge(JBUI.Borders.empty(5), RoundedLineBorder(JBColor.LIGHT_GRAY, 4, 1), true)
        lineWrap = true
        wrapStyleWord = true
    }

    private val extraLineCheckBox = JBCheckBox("Add an extra line between files")
    private val setMaxFilesCheckBox = JBCheckBox("Set maximum number of files to have their content copied")
    private val maxFilesField = JBTextField(10)
    private val maxFileSizeField = JBTextField(10)
    private val showNotificationCheckBox = JBCheckBox("Show notification after copying")
    private val useFilenameFiltersCheckBox = JBCheckBox("Enable include rules") // legacy toggle, drives "include rules on/off"
    private val strictMemoryReadCheckBox = JBCheckBox("Strict memory reading (only read from memory if file is open in editor)")

    private val warningLabel = JLabel("<html><b>Warning:</b> Not setting a maximum number of files may cause high memory usage.</html>").apply {
        foreground = JBColor(0xA94442, 0xA94442)
        background = JBColor(0xF2DEDE, 0xF2DEDE)
        border = JBUI.Borders.compound(JBUI.Borders.empty(5), BorderFactory.createLineBorder(JBColor(0xEBCCD1, 0xEBCCD1)))
        isOpaque = true
        isVisible = false
    }

    // ---------- Filtering UI (tables + toggles) ----------
    private val includeTable = RuleTable()
    private val excludeTable = RuleTable()

    private val includeInfoLabel = JLabel("<html><b>Info:</b> When disabled, all files are candidates. Add rules to restrict what’s included.</html>").apply {
        foreground = JBColor(0x31708F, 0x31708F)
        background = JBColor(0xD9EDF7, 0xD9EDF7)
        border = JBUI.Borders.compound(JBUI.Borders.empty(5), BorderFactory.createLineBorder(JBColor(0xBCE8F1, 0xBCE8F1)))
        isOpaque = true
        isVisible = false
    }

    private val useExcludeRulesCheck = JBCheckBox("Enable exclude rules")
    private val matchDirectoriesCheck = JBCheckBox("Allow rules to gate directories")
    private val matchHiddenFilesCheck = JBCheckBox("Include hidden files (dotfiles)")
    private val overlapPolicyBox = JComboBox(OverlapPolicy.entries.toTypedArray())

    private lateinit var includeDecorated: JComponent
    private lateinit var excludeDecorated: JComponent
    private val previewButton = JButton("Preview…")

    // ---------- Lifecycle ----------
    init {
        setMaxFilesCheckBox.addActionListener {
            val on = setMaxFilesCheckBox.isSelected
            maxFilesField.isVisible = on
            warningLabel.isVisible = !on
        }
        useFilenameFiltersCheckBox.addActionListener { updateIncludeEnabledState() }
        useExcludeRulesCheck.addActionListener { updateExcludeEnabledState() }
        previewButton.addActionListener { openPreviewDialog() }
    }

    // ---------- Configurable ----------
    override fun createComponent(): JComponent {
        settings = CopyFileContentSettings.getInstance(project)

        // Constraints section
        val constraintsPanel = borderedSection("Constraints for copying") {
            add(inlineRow(createWrappedCheckBoxPanel(setMaxFilesCheckBox), maxFilesField))
            add(inlineRow(JLabel(), warningLabel))
            add(labeledRow("Maximum file size (KB):", maxFileSizeField))
            // Keep legacy include toggle here so it’s obvious this controls whether include rules are active
            add(createWrappedCheckBoxPanel(useFilenameFiltersCheckBox))
        }

        // Filtering section (tabs + global row)
        val filteringPanel = borderedSection("Filtering") {
            add(buildFilteringTabs())
            add(Box.createVerticalStrut(8))
            add(buildFilteringGlobalRow())
        }

        // Text structure section
        val textPanel = borderedSection("Text structure of what's going to the clipboard") {
            add(labeledRow("Pre Text:", JBScrollPane(preTextArea)))
            val headerRow = JPanel(BorderLayout())
            headerRow.add(JBScrollPane(headerFormatArea), BorderLayout.CENTER)
            val headerHelp = JBLabel("<html><small>Use <code>\$FILE_PATH</code> to insert the file’s path. Example: <code>// file: \$FILE_PATH</code></small></html>")
            headerHelp.border = JBUI.Borders.emptyLeft(5)
            headerRow.add(headerHelp, BorderLayout.SOUTH)
            add(labeledRow("File Header Format:", headerRow))
            add(labeledRow("Post Text:", JBScrollPane(postTextArea)))
            add(createWrappedCheckBoxPanel(extraLineCheckBox))
        }

        // Reading / Feedback
        val behaviorPanel = borderedSection("File reading & feedback") {
            add(createWrappedCheckBoxPanel(strictMemoryReadCheckBox))
            add(createWrappedCheckBoxPanel(showNotificationCheckBox))
        }

        // Page
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.border = JBUI.Borders.empty(8)

        root.add(constraintsPanel)
        root.add(Box.createVerticalStrut(10))
        root.add(filteringPanel)
        root.add(Box.createVerticalStrut(10))
        root.add(textPanel)
        root.add(Box.createVerticalStrut(10))
        root.add(behaviorPanel)

        // Initialize visibility/enabled
        maxFilesField.isVisible = setMaxFilesCheckBox.isSelected
        warningLabel.isVisible = !setMaxFilesCheckBox.isSelected
        updateIncludeEnabledState()
        updateExcludeEnabledState()

        return root
    }

    override fun getDisplayName(): String = "Copy File Content Settings"

    // ---------- Apply / Reset / Modified ----------
    override fun isModified(): Boolean {
        return settings?.let { current ->
            val s = current.state
            val includeRules = includeTable.readRules()
            val excludeRules = excludeTable.readRules()
            headerFormatArea.text != s.headerFormat ||
            preTextArea.text != s.preText ||
            postTextArea.text != s.postText ||
            extraLineCheckBox.isSelected != s.addExtraLineBetweenFiles ||
            setMaxFilesCheckBox.isSelected != s.setMaxFileCount ||
            (setMaxFilesCheckBox.isSelected && maxFilesField.text.toIntOrNull() != s.fileCountLimit) ||
            maxFileSizeField.text.toIntOrNull() != s.maxFileSizeKB ||
            showNotificationCheckBox.isSelected != s.showCopyNotification ||
            useFilenameFiltersCheckBox.isSelected != s.useFilenameFilters ||
            strictMemoryReadCheckBox.isSelected != s.strictMemoryRead ||
            includeRules != s.includeRules ||
            excludeRules != s.excludeRules ||
            useExcludeRulesCheck.isSelected != s.useExcludeRules ||
            matchDirectoriesCheck.isSelected != s.matchDirectories ||
            matchHiddenFilesCheck.isSelected != s.matchHiddenFiles ||
            overlapPolicyBox.selectedItem != s.overlapPolicy
        } ?: false
    }

    override fun apply() {
        settings?.let { current ->
            val s = current.state
            s.headerFormat = headerFormatArea.text
            s.preText = preTextArea.text
            s.postText = postTextArea.text
            s.addExtraLineBetweenFiles = extraLineCheckBox.isSelected
            s.setMaxFileCount = setMaxFilesCheckBox.isSelected
            s.fileCountLimit = maxFilesField.text.toIntOrNull() ?: s.fileCountLimit
            s.maxFileSizeKB = maxFileSizeField.text.toIntOrNull() ?: s.maxFileSizeKB
            s.showCopyNotification = showNotificationCheckBox.isSelected
            s.useFilenameFilters = useFilenameFiltersCheckBox.isSelected
            s.strictMemoryRead = strictMemoryReadCheckBox.isSelected

            s.includeRules = includeTable.readRules()
            s.excludeRules = excludeTable.readRules()
            s.useExcludeRules = useExcludeRulesCheck.isSelected
            s.matchDirectories = matchDirectoriesCheck.isSelected
            s.matchHiddenFiles = matchHiddenFilesCheck.isSelected
            s.overlapPolicy = overlapPolicyBox.selectedItem as OverlapPolicy
        }
    }

    override fun reset() {
        settings?.let { current ->
            val s = current.state
            headerFormatArea.text = s.headerFormat
            preTextArea.text = s.preText
            postTextArea.text = s.postText
            extraLineCheckBox.isSelected = s.addExtraLineBetweenFiles
            setMaxFilesCheckBox.isSelected = s.setMaxFileCount
            maxFilesField.text = s.fileCountLimit.toString()
            maxFileSizeField.text = s.maxFileSizeKB.toString()
            showNotificationCheckBox.isSelected = s.showCopyNotification
            useFilenameFiltersCheckBox.isSelected = s.useFilenameFilters
            strictMemoryReadCheckBox.isSelected = s.strictMemoryRead

            includeTable.setRules(s.includeRules)
            excludeTable.setRules(s.excludeRules)
            useExcludeRulesCheck.isSelected = s.useExcludeRules
            matchDirectoriesCheck.isSelected = s.matchDirectories
            matchHiddenFilesCheck.isSelected = s.matchHiddenFiles
            overlapPolicyBox.selectedItem = s.overlapPolicy

            maxFilesField.isVisible = s.setMaxFileCount
            warningLabel.isVisible = !s.setMaxFileCount
            updateIncludeEnabledState()
            updateExcludeEnabledState()
            updateIncludeInfoVisibility()
        }
    }

    // ---------- Builders ----------

    private fun borderedSection(title: String, builder: JPanel.() -> Unit): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = IdeBorderFactory.createTitledBorder(title, false, JBUI.insetsTop(12))
        panel.builder()
        return panel
    }

    private fun labeledRow(title: String, component: JComponent): JPanel {
        val row = JPanel(BorderLayout())
        val label = JLabel(title)
        label.border = JBUI.Borders.emptyBottom(4)
        row.add(label, BorderLayout.NORTH)
        row.add(component, BorderLayout.CENTER)
        row.border = JBUI.Borders.emptyBottom(10)
        return row
    }

    private fun inlineRow(left: JComponent, right: JComponent, spacing: Int = 10): JPanel {
        val panel = JPanel(BorderLayout())
        val leftWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        leftWrap.add(left)
        leftWrap.border = JBUI.Borders.emptyRight(spacing)
        panel.add(leftWrap, BorderLayout.WEST)
        panel.add(right, BorderLayout.CENTER)
        panel.border = JBUI.Borders.emptyBottom(10)
        return panel
    }

    private fun createWrappedCheckBoxPanel(checkBox: JBCheckBox, paddingTop: Int = 4): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.emptyTop(paddingTop).let { JBUI.Borders.merge(it, JBUI.Borders.emptyBottom(10), true) }
        panel.add(checkBox, BorderLayout.WEST)
        return panel
    }

    // Tabs for Include / Exclude with native toolbar decorators
    private fun buildFilteringTabs(): JComponent {
        // Include tab
        val includePresetAction = object : com.intellij.ui.AnActionButton("Apply Preset…") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val merged = (includeTable.readRules() + RulePresets.CommonSourceFiles).distinct()
                includeTable.setRules(merged)
                useFilenameFiltersCheckBox.isSelected = true
                updateIncludeEnabledState()
                updateIncludeInfoVisibility()
            }
        }
        includeDecorated = ToolbarDecorator.createDecorator(includeTable)
            .setAddAction { includeTable.addEmptyRow() }
            .setRemoveAction { includeTable.removeSelectedRow() }
            .disableUpAction().disableDownAction()
            .addExtraAction(includePresetAction)
            .createPanel()

        val includeTopRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(useFilenameFiltersCheckBox)
        }
        val includeTab = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(includeTopRow, BorderLayout.NORTH)
            add(includeDecorated, BorderLayout.CENTER)
            add(includeInfoLabel, BorderLayout.SOUTH)
        }

        // Exclude tab
        val excludePresetAction = object : com.intellij.ui.AnActionButton("Apply Preset…") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val merged = (excludeTable.readRules() + RulePresets.IgnoreBuildOutputs).distinct()
                excludeTable.setRules(merged)
                useExcludeRulesCheck.isSelected = true
                updateExcludeEnabledState()
            }
        }
        excludeDecorated = ToolbarDecorator.createDecorator(excludeTable)
            .setAddAction { excludeTable.addEmptyRow() }
            .setRemoveAction { excludeTable.removeSelectedRow() }
            .disableUpAction().disableDownAction()
            .addExtraAction(excludePresetAction)
            .createPanel()

        val excludeTopRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(useExcludeRulesCheck)
        }
        val excludeTab = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(excludeTopRow, BorderLayout.NORTH)
            add(excludeDecorated, BorderLayout.CENTER)
        }

        val tabs = JBTabbedPane(SwingConstants.TOP)
        tabs.addTab("Include", includeTab)
        tabs.addTab("Exclude", excludeTab)

        return tabs
    }

    private fun buildFilteringGlobalRow(): JComponent {
        val row = JPanel(HorizontalLayout(12))
        row.border = JBUI.Borders.emptyTop(6)
        row.add(matchDirectoriesCheck)
        row.add(matchHiddenFilesCheck)

        // overlap policy inline
        val policyPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        policyPanel.add(JLabel("Overlap policy:"))
        policyPanel.add(overlapPolicyBox)
        row.add(policyPanel)

        row.add(Box.createHorizontalStrut(12))
        row.add(previewButton)
        return row
    }

    // ---------- UI state helpers ----------
    private fun updateIncludeEnabledState() {
        val enabled = useFilenameFiltersCheckBox.isSelected
        if (this::includeDecorated.isInitialized) UIUtil.setEnabled(includeDecorated, enabled, true)
        updateIncludeInfoVisibility()
    }

    private fun updateExcludeEnabledState() {
        val enabled = useExcludeRulesCheck.isSelected
        if (this::excludeDecorated.isInitialized) UIUtil.setEnabled(excludeDecorated, enabled, true)
    }

    private fun updateIncludeInfoVisibility() {
        includeInfoLabel.isVisible = useFilenameFiltersCheckBox.isSelected && includeTable.modelImpl.rowCount == 0
    }

    private fun openPreviewDialog() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, true, false, false, false, false).apply {
            title = "Select root to preview"
        }
        val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) ?: return
        val stateSnapshot = settings?.state?.copy(
            includeRules = includeTable.readRules(),
            excludeRules = excludeTable.readRules(),
            useExcludeRules = useExcludeRulesCheck.isSelected,
            matchDirectories = matchDirectoriesCheck.isSelected,
            matchHiddenFiles = matchHiddenFilesCheck.isSelected,
            overlapPolicy = overlapPolicyBox.selectedItem as OverlapPolicy,
            useFilenameFilters = useFilenameFiltersCheckBox.isSelected,
        ) ?: return
        val projectRoot = ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
        FilterPreviewDialog(project, chosen) { _ ->
            com.github.mwguerra.copyfilecontent.filter.FilterEngine(stateSnapshot, projectRoot ?: chosen)
        }.show()
    }
}
