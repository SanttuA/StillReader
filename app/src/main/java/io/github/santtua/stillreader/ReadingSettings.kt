package io.github.santtua.stillreader

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

internal fun showReadingSettings(
    activity: Activity,
    current: ReadingComfort,
    onApply: (ReadingComfort) -> Unit,
    onFullscreen: (() -> Unit)? = null
) {
    val dialogTheme = when (current.theme.resolve(activity.resources.configuration)) {
        ReadingTheme.DARK, ReadingTheme.HIGH_CONTRAST -> android.R.style.Theme_Material_Dialog_Alert
        else -> android.R.style.Theme_Material_Light_Dialog_Alert
    }
    val builder = AlertDialog.Builder(activity, dialogTheme)
    val context = builder.context
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val inset = (24 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset / 2, inset, 0)
    }

    fun selector(label: Int, values: List<Int>, index: Int): Spinner {
        val spinner = Spinner(context).apply {
            id = View.generateViewId()
            minimumHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
            adapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, values.map(context::getString)
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(index)
        }
        panel.addView(TextView(context).apply {
            setText(label)
            labelFor = spinner.id
        })
        panel.addView(spinner)
        return spinner
    }

    fun toggle(label: Int, checked: Boolean) = CheckBox(context).apply {
        setText(label)
        isChecked = checked
        panel.addView(this)
    }

    val themes = ReadingTheme.entries
    val theme = selector(R.string.reading_theme, themes.map { it.label }, current.theme.ordinal)
    val recolor = toggle(R.string.recolor_pdf, current.recolor)
    val fit = toggle(R.string.fit_page, current.fitPage)
    val awake = toggle(R.string.keep_screen_awake, current.keepAwake)
    val amounts = listOf(0, 5, 10)
    val crop = selector(R.string.crop_margins,
        listOf(R.string.crop_off, R.string.crop_five, R.string.crop_ten),
        amounts.indexOf(current.crop).coerceAtLeast(0)
    )

    fun save() = onApply(ReadingComfort(
        themes[theme.selectedItemPosition], recolor.isChecked, fit.isChecked,
        awake.isChecked, amounts[crop.selectedItemPosition]
    ))

    builder
        .setTitle(if (onFullscreen == null) R.string.default_reading_settings else R.string.reading_settings)
        .setView(ScrollView(context).apply { addView(panel) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.apply_reading_settings) { _, _ -> save() }
    if (onFullscreen != null) builder.setNeutralButton(R.string.enter_fullscreen) { _, _ ->
        save()
        onFullscreen()
    }
    val dialog = builder.create()
    dialog.show()
    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isAllCaps = false
    dialog.getButton(DialogInterface.BUTTON_POSITIVE).isAllCaps = false
    dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.isAllCaps = false
}
