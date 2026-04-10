package com.example.lpsplugin

import javax.swing.table.AbstractTableModel

class LPSTableModel(private var columnNames: Array<String>) : AbstractTableModel() {
    private val rows = mutableListOf<Array<String>>()
    private val MAX_ROWS = 10000

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columnNames.size
    override fun getColumnName(column: Int): String = columnNames[column]
    
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        return rows.getOrNull(rowIndex)?.getOrNull(columnIndex)
    }

    fun setColumnNames(newNames: Array<String>) {
        this.columnNames = newNames
        fireTableStructureChanged()
    }

    fun addRow(row: Array<String>) {
        rows.add(row)
        if (rows.size > MAX_ROWS) {
            rows.removeAt(0)
            fireTableDataChanged()
        } else {
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }
    }

    fun addRows(newRows: List<Array<String>>) {
        if (newRows.isEmpty()) return
        val start = rows.size
        rows.addAll(newRows)
        if (rows.size > MAX_ROWS) {
            val toRemove = rows.size - MAX_ROWS
            repeat(toRemove) { rows.removeAt(0) }
            fireTableDataChanged()
        } else {
            fireTableRowsInserted(start, (rows.size - 1).coerceAtLeast(0))
        }
    }

    fun clear() {
        val size = rows.size
        rows.clear()
        if (size > 0) fireTableRowsDeleted(0, size - 1)
    }
    
    fun getRow(index: Int): Array<String>? = rows.getOrNull(index)
}
