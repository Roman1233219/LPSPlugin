package io.github.Roman1233219.lpsplugin

import javax.swing.table.DefaultTableModel

class LPSTableModel(columnNames: Array<String>) : DefaultTableModel(columnNames, 0) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false

    fun addRows(newRows: List<Array<String>>) {
        newRows.forEach { addRow(it) }
    }

    fun clear() {
        rowCount = 0
    }

    fun getRow(row: Int): Array<String>? {
        if (row < 0 || row >= rowCount) return null
        val cols = columnCount
        return Array(cols) { getValueAt(row, it).toString() }
    }
    
    fun setColumnNames(names: Array<String>) {
        columnIdentifiers.clear()
        names.forEach { columnIdentifiers.add(it) }
        fireTableStructureChanged()
    }
}
