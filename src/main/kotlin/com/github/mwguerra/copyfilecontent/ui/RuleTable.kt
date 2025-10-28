package com.github.mwguerra.copyfilecontent.ui

import com.github.mwguerra.copyfilecontent.filter.FilterRule
import com.github.mwguerra.copyfilecontent.filter.MatchType
import com.intellij.ui.table.JBTable
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.table.DefaultTableModel

private const val COLUMN_ENABLED = 0
private const val COLUMN_TYPE = 1
private const val COLUMN_PATTERN = 2
private const val COLUMN_CASE_SENSITIVE = 3
private const val COLUMN_APPLY_TO_DIRECTORIES = 4

class RuleTableModel : DefaultTableModel() {
    init {
        setColumnIdentifiers(arrayOf("Enabled", "Type", "Pattern", "Case Sensitive", "Apply to Directories"))
    }

    override fun isCellEditable(row: Int, column: Int): Boolean = true

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COLUMN_ENABLED, COLUMN_CASE_SENSITIVE, COLUMN_APPLY_TO_DIRECTORIES -> java.lang.Boolean::class.java
        COLUMN_TYPE -> MatchType::class.java
        else -> String::class.java
    }
}

class RuleTable : JBTable() {
    val modelImpl = RuleTableModel()

    init {
        model = modelImpl
        tableHeader.reorderingAllowed = false
        setShowGrid(true)

        val typeEditor = JComboBox<MatchType>(MatchType.entries.toTypedArray())
        columnModel.getColumn(COLUMN_TYPE).cellEditor = DefaultCellEditor(typeEditor)
    }

    fun setRules(rules: List<FilterRule>) {
        modelImpl.rowCount = 0
        rules.forEach { rule ->
            modelImpl.addRow(
                arrayOf<Any>(
                    rule.enabled,
                    rule.type,
                    rule.pattern,
                    rule.caseSensitive,
                    rule.applyToDirectories,
                ),
            )
        }
    }

    fun readRules(): MutableList<FilterRule> {
        val rules = mutableListOf<FilterRule>()
        for (row in 0 until modelImpl.rowCount) {
            rules.add(
                FilterRule(
                    enabled = (modelImpl.getValueAt(row, COLUMN_ENABLED) as? Boolean) ?: true,
                    type = (modelImpl.getValueAt(row, COLUMN_TYPE) as? MatchType) ?: MatchType.EXTENSION,
                    pattern = (modelImpl.getValueAt(row, COLUMN_PATTERN) as? String)?.trim().orEmpty(),
                    caseSensitive = (modelImpl.getValueAt(row, COLUMN_CASE_SENSITIVE) as? Boolean) ?: false,
                    applyToDirectories = (modelImpl.getValueAt(row, COLUMN_APPLY_TO_DIRECTORIES) as? Boolean) ?: false,
                ),
            )
        }
        return rules
    }

    fun addEmptyRow() {
        modelImpl.addRow(arrayOf<Any>(true, MatchType.EXTENSION, "", false, false))
    }

    fun removeSelectedRow() {
        val index = selectedRow
        if (index >= 0) {
            modelImpl.removeRow(index)
        }
    }
}

fun RuleTable.wrapWithScroll(): JScrollPane = JScrollPane(this)
