package com.mraba7.deliverylocation

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class ProfileAdapter(context: Context, private val items: List<String>) :
    ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {

    private val palette = intArrayOf(
        R.color.profile_1, R.color.profile_2, R.color.profile_3,
        R.color.profile_4, R.color.profile_5, R.color.profile_6
    )

    fun colorForName(name: String): Int {
        val idx = items.indexOf(name).let { if (it < 0) 0 else it }
        return ContextCompat.getColor(context, palette[idx % palette.size])
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return buildRow(position)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return buildRow(position)
    }

    private fun buildRow(position: Int): View {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val padding = (10 * density).toInt()
        row.setPadding(padding, padding, padding, padding)

        val dot = View(context)
        val size = (14 * density).toInt()
        val lp = LinearLayout.LayoutParams(size, size)
        lp.marginEnd = (8 * density).toInt()
        dot.layoutParams = lp
        dot.setBackgroundColor(colorForName(items[position]))

        val text = TextView(context)
        text.text = items[position]
        text.setTextColor(ContextCompat.getColor(context, R.color.text_light))
        text.textSize = 15f

        row.addView(dot)
        row.addView(text)
        return row
    }
}
