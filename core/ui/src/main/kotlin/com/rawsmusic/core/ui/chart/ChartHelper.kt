package com.rawsmusic.core.ui.chart

import android.content.Context
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.utils.ColorTemplate

object ChartHelper {

    fun buildFormatDistributionData(
        distribution: Map<String, Int>
    ): List<PieEntry> {
        return distribution.map { (format, count) ->
            PieEntry(count.toFloat(), format.uppercase())
        }.sortedByDescending { it.value }
    }

    fun buildPieDataSet(
        entries: List<PieEntry>,
        label: String = ""
    ): PieDataSet {
        return PieDataSet(entries, label).apply {
            colors = ColorTemplate.MATERIAL_COLORS.toList()
            sliceSpace = 2f
            selectionShift = 8f
        }
    }

    fun buildPieData(dataSet: PieDataSet): PieData {
        return PieData(dataSet).apply {
            setValueTextSize(12f)
        }
    }
}
