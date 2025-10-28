package com.github.mwguerra.copyfilecontent.ui

import com.github.mwguerra.copyfilecontent.filter.FilterEngine
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel

class FilterPreviewDialog(
    project: Project,
    private val root: VirtualFile,
    private val engineFactory: (VirtualFile) -> FilterEngine,
) : DialogWrapper(project, true) {

    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private val onlyIncludedCheck = JBCheckBox("Show only included", true)
    private val summaryLabel = JBLabel()

    init {
        title = "Filter Preview"
        init()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(12)

        onlyIncludedCheck.addActionListener { refresh() }

        panel.add(onlyIncludedCheck, BorderLayout.NORTH)
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        panel.add(summaryLabel, BorderLayout.SOUTH)
        return panel
    }

    private fun refresh() {
        model.clear()
        val engine = engineFactory(root)
        var included = 0
        var excluded = 0

        fun visit(file: VirtualFile) {
            if (file.isDirectory) {
                if (!engine.shouldEnterDirectory(file)) {
                    excluded++
                    if (!onlyIncludedCheck.isSelected) {
                        model.addElement("✗ ${file.path}")
                    }
                    return
                }
                file.children.forEach { visit(it) }
            } else {
                val (allow, _) = engine.shouldInclude(file)
                if (allow) {
                    included++
                    if (onlyIncludedCheck.isSelected) {
                        model.addElement("✓ ${file.path}")
                    }
                } else {
                    excluded++
                    if (!onlyIncludedCheck.isSelected) {
                        model.addElement("✗ ${file.path}")
                    }
                }
            }
        }

        visit(root)
        if (model.isEmpty()) {
            model.addElement("(no files to display)")
        }
        summaryLabel.text = "Included: $included, Excluded: $excluded"
    }
}
