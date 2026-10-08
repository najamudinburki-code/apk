package com.example.systemhealth

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The pieces every screen here is built from: cards, labelled state rows, buttons with a clear weight,
 * switches that explain themselves, sections that open and close, and a search box.
 *
 * Sizes are density-independent and gaps widen with the owner's font setting, so a large system text
 * size stretches the page instead of clipping it. Colours come from the active theme rather than from
 * constants in this file, which is what lets one layout read correctly in light and in dark.
 */
internal class ScreenKit(private val activity: Activity) {

    enum class Tone { PLAIN, EMPHASIS, ALERT }
    enum class Weight { PRIMARY, PLAIN, QUIET, STOP }

    /** Open and closed detail blocks, kept so a rotation does not fold the page back up. */
    val sectionStates = LinkedHashMap<String, Boolean>()

    private val cards = mutableListOf<Card>()

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(space(16), space(12), space(16), space(28))
    }

    private val metrics get() = activity.resources.displayMetrics

    private fun dp(points: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, points.toFloat(), metrics).toInt()

    /** Spacing follows the text size within limits, so huge fonts do not push controls off the page.
     * The font scale lives on the configuration, not on DisplayMetrics, which only mirrors it as
     * scaledDensity. */
    private fun space(points: Int): Int = (dp(points) *
        (activity.resources.configuration.fontScale.coerceIn(1f, 1.5f))).toInt()

    private fun resolve(attribute: Int, fallback: Int) = activity.themeColor(attribute, fallback)

    /** The card fill. Android's own panel attribute is a state list, so only a plain colour is usable
     * here; anything else falls back to the theme background or a measured surface. */
    private fun surface(): Int = activity.surfaceColor()

    fun scrollContent(): ScrollView = ScrollView(activity).apply {
        addView(root)
        isFillViewport = true
    }

    fun eyebrow(text: String): TextView = addText(root, text, 13f, false,
        android.R.attr.textColorSecondary)

    fun screenTitle(text: String): TextView = addText(root, text, 26f, true,
        android.R.attr.textColorPrimary)

    /** A sentence belonging to no card: the explanation of a whole part of the screen. */
    fun paragraph(text: String): TextView = addText(root, text, 15f, false,
        android.R.attr.textColorSecondary)

    fun gap(points: Int = 8) {
        root.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, space(points))
        })
    }

    fun card(title: String? = null, tone: Tone = Tone.PLAIN): Card {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(space(16), space(12), space(16), space(14))
            background = panel(tone)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = space(12) }
        }
        val heading = title?.let {
            addText(container, it, 19f, true, android.R.attr.textColorPrimary, heading = true)
        }
        root.addView(container)
        return Card(container, heading).also { cards += it }
    }

    /** A card that folds away. Its header stays on the page whatever the body is filtered down to. */
    fun expandable(id: String, closed: String, open: String, tone: Tone = Tone.PLAIN,
        body: (Card) -> Unit): Card {
        val opened = sectionStates.getOrPut(id) { false }
        val details = card(null, tone)
        details.collapsed = !opened
        body(details)
        val header = Button(activity).apply {
            isAllCaps = false
            textSize = 16f
            setMinimumHeight(space(48))
            text = if (opened) open else closed
            describeFold(closed, opened)
            setOnClickListener {
                val now = details.view.visibility != View.VISIBLE
                sectionStates[id] = now
                details.collapsed = !now
                details.applyVisibility()
                text = if (now) open else closed
                describeFold(closed, now)
            }
        }
        root.addView(header, root.indexOfChild(details.view))
        details.applyVisibility()
        return details
    }

    private fun Button.describeFold(closed: String, open: Boolean) {
        contentDescription = "$closed, ${if (open) "expanded" else "collapsed"}. " +
            "Tap to ${if (open) "hide" else "show"}."
    }

    /** Narrows every card to the tools the words match. Untagged rows are state, and a search never
     * hides state or a Stop control. */
    fun filter(query: String) {
        val keep = ToolCatalog.matching(query)
        cards.forEach { it.applyFilter(keep) }
    }

    private fun panel(tone: Tone): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(panelColor())
        setStroke(dp(if (tone == Tone.PLAIN) 1 else 2), outline(tone))
    }

    /** A card painted with exactly the page colour reads as a floating paragraph, so the panel is
     * lifted a few percent toward the text colour in both themes. */
    private fun panelColor(): Int = activity.panelColor()

    private fun outline(tone: Tone): Int = when (tone) {
        Tone.EMPHASIS -> accentColor()
        Tone.ALERT -> resolve(android.R.attr.colorError, ALERT)
        Tone.PLAIN -> withAlpha(resolve(android.R.attr.textColorSecondary, OUTLINE), 0x55)
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Channels added together, because two colours this close are the same colour on a phone screen. */
    private fun distinguishableFrom(color: Int, background: Int): Boolean =
        abs(Color.red(color) - Color.red(background)) +
            abs(Color.green(color) - Color.green(background)) +
            abs(Color.blue(color) - Color.blue(background)) > INDISTINGUISHABLE

    private fun addText(parent: LinearLayout, text: String, size: Float, bold: Boolean,
        colorAttribute: Int, heading: Boolean = false): TextView {
        val view = TextView(activity).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTypeface(typeface, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(readableInk(resolve(colorAttribute, primaryText()), activity.panelColor()))
            setPadding(0, space(4), 0, space(4))
        }
        if (heading) ViewCompat.setAccessibilityHeading(view, true)
        parent.addView(view)
        return view
    }

    private fun primaryText() = if (activity.isNight()) NIGHT_TEXT else DAY_TEXT
    private fun alertColor() = resolve(android.R.attr.colorError, ALERT)

    /** Error red is a border colour. As running text it has to clear the panel it sits on, and HiOS
     * ships a `colorError` of #FF5722 that only reaches 2.4:1 on its own light card. */
    private fun alertInk(): Int = readableInk(alertColor(), panelColor())

    /** The theme's own accent, but only one that can actually be seen on the page. HiOS ships a dark
     * `colorPrimary` equal to its window background, which painted the primary button invisible. */
    private fun accentColor(): Int {
        val page = surface()
        return intArrayOf(resolve(android.R.attr.colorAccent, ACCENT),
            resolve(android.R.attr.colorControlActivated, ACCENT), ACCENT)
            .first { distinguishableFrom(it, page) }
    }

    /** One row of labelled state, kept so a refresh updates the words instead of rebuilding the page. */
    class StateRow internal constructor(val view: View, val value: TextView)

    /** One card's worth of controls. Anything added is searchable unless it is state or a Stop. */
    inner class Card internal constructor(val view: LinearLayout, private val heading: TextView?) {

        private val items = mutableListOf<Entry>()
        private val generated = mutableListOf<View>()
        /** Set by [expandable], which owns the fold, so a closed block leaves the page entirely. */
        internal var collapsed = false

        private fun <T : View> add(row: T, id: String? = null, keepVisible: Boolean = false): T {
            if (row.parent == null) view.addView(row)
            items += Entry(row, id, keepVisible)
            applyVisibility()
            return row
        }

        internal fun applyFilter(keep: Set<String>?) {
            items.forEach { entry ->
                entry.view.visibility = if (keep == null || entry.keepVisible ||
                        (entry.id != null && entry.id in keep)) View.VISIBLE else View.GONE
            }
            applyVisibility()
        }

        /** A card leaves the page only when nothing inside it survives filtering or it is folded up. */
        internal fun applyVisibility() {
            val shown = !collapsed && items.any { it.view.visibility == View.VISIBLE }
            view.visibility = if (shown) View.VISIBLE else View.GONE
            heading?.visibility = if (shown) View.VISIBLE else View.GONE
        }

        /** Replaces sentences that come and go, like the reasons a phone needs its owner. Rows the
         * screen built once are left alone, so a refresh never disturbs the reading order. */
        fun replaceLines(lines: List<String>) {
            clearGenerated()
            lines.forEach { line ->
                val row = TextView(activity).apply {
                    textSize = 15f
                    setTextColor(alertInk())
                    setPadding(0, space(4), 0, space(4))
                    setMinimumHeight(space(32))
                }
                row.text = line
                add(row, keepVisible = true)
                generated += row
            }
        }

        /** Replaces labelled state rows whose number changes with what the phone is doing. */
        fun replaceFacts(facts: List<Fact>) {
            clearGenerated()
            facts.forEach { fact ->
                val row = stateView(fact.label, fact.value, fact.needsAttention)
                add(row, keepVisible = true)
                generated += row
            }
        }

        private fun clearGenerated() {
            generated.forEach { row ->
                view.removeView(row)
                items.removeAll { it.view === row }
            }
            generated.clear()
        }

        fun note(text: String, id: String? = null): TextView =
            add(addText(view, text, 14f, false, android.R.attr.textColorSecondary), id)

        /** A line of explanation under a button, in the quieter colour but still a real label. */
        fun underButton(text: String, id: String? = null): TextView =
            add(addText(view, text, 13f, false, android.R.attr.textColorSecondary), id)

        /** Anything a screen needs that this kit has no builder for, registered so search can see it. */
        fun <T : View> content(row: T, id: String? = null): T = add(row, id, keepVisible = true)

        /** A label over a value that changes on its own. Returns the row so it can be refreshed. */
        fun state(label: String, value: String, attention: Boolean = false): StateRow {
            val row = stateView(label, value, attention)
            add(row, keepVisible = true)
            return StateRow(row, row.getChildAt(1) as TextView)
        }

        private fun stateView(label: String, value: String, attention: Boolean): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, space(5), 0, space(5))
                gravity = Gravity.CENTER_VERTICAL
                setMinimumHeight(space(38))
            }
            addText(row, label, 13f, false, android.R.attr.textColorSecondary)
            val field = TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            }
            row.addView(field)
            field.valueText(value, attention)
            ViewCompat.setAccessibilityLiveRegion(row, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
            return row
        }

        /** [staysVisible] pins a control whatever the search box says: the way out of a screen is not
         * one of the tools a person is trying to filter away. */
        fun button(label: String, weight: Weight = Weight.PLAIN, id: String? = null,
            staysVisible: Boolean = false, onClick: () -> Unit): Button {
            val button = Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = if (weight == Weight.PRIMARY) 18f else 16f
                setMinimumHeight(space(if (weight == Weight.PRIMARY) 54 else 48))
                setOnClickListener { onClick() }
                when (weight) {
                    Weight.PRIMARY -> filled(this)
                    Weight.STOP -> outlined(this, alertColor())
                    Weight.QUIET -> setTextColor(resolve(android.R.attr.textColorSecondary, primaryText()))
                    Weight.PLAIN -> Unit
                }
            }
            return add(button, id, keepVisible = staysVisible || weight == Weight.STOP)
        }

        /** A control plus what it changes: an unlabelled switch is the thing people are afraid to tap. */
        fun toggle(title: String, explanation: String = "", initial: Boolean = false,
            id: String? = null, onChange: (Boolean) -> Unit): CheckBox {
            val box = CheckBox(activity).apply {
                text = title
                isChecked = initial
                textSize = 16f
                setMinimumHeight(space(48))
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(resolve(android.R.attr.textColorPrimary, primaryText()))
                contentDescription = if (explanation.isBlank()) title else "$title. $explanation"
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            add(box, id)
            if (explanation.isNotBlank()) note(explanation, id)
            return box
        }

        fun choice(prompt: String, names: List<String>, selected: Int, id: String? = null,
            onChange: (Int) -> Unit): Spinner {
            note(prompt, id)
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(selected.coerceIn(0, names.size - 1))
                contentDescription = prompt
                setMinimumHeight(space(48))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, itemId: Long) =
                        onChange(position)
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
            return add(spinner, id)
        }

        /** Fills come from the theme, so the text on them is chosen by measured contrast, not habit.
         * Press feedback is a darker copy of the same shape, because a RippleDrawable given no mask
         * never painted its content on this handset — the button stayed the colour of the card. */
        private fun filled(button: Button) {
            val fill = accentColor()
            val ink = readableOn(fill)
            button.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), rounded(mixChannels(fill, ink, 0.22f)))
                addState(intArrayOf(), rounded(fill))
            }
            button.setTextColor(ink)
        }

        /** The outline keeps the loud colour; the words on it get the version of it that can be read. */
        private fun outlined(button: Button, color: Int) {
            button.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(surface())
                setStroke(dp(2), color)
            }
            button.setTextColor(readableInk(color, surface()))
        }

        private fun rounded(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(color)
        }
    }

    /** One control on a card, with the search word it answers to. Declared out here because Kotlin
     * forbids a nested class inside an inner class. */
    private class Entry(val view: View, val id: String?, val keepVisible: Boolean)

    /** The search box for a long list of tools. Empty means show everything. */
    fun searchField(hint: String, onChange: (String) -> Unit): EditText {
        val field = EditText(activity).apply {
            this.hint = hint
            contentDescription = hint
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setMaxLines(1)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(space(14), space(12), space(14), space(12))
            background = panel(Tone.PLAIN)
            setMinimumHeight(space(48))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = space(10) }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: android.text.Editable?) = onChange(text?.toString().orEmpty())
        })
        root.addView(field)
        return field
    }
}

/** Sets a state value and how loudly it should be shown. Colors resolve from the view's own theme. */
internal fun TextView.valueText(value: String, attention: Boolean = false) {
    text = value
    val context = context
    val attribute = if (attention) android.R.attr.colorError else android.R.attr.textColorPrimary
    val fallback = if (attention) ALERT else if (context.isNight()) NIGHT_TEXT else DAY_TEXT
    setTextColor(readableInk(context.themeColor(attribute, fallback), context.panelColor()))
}

/** The page fill for this theme. Android's own panel attribute is a state list, so a plain colour is
 * the only usable thing to paint a card with. */
internal fun android.content.Context.surfaceColor(): Int =
    themeColor(android.R.attr.colorBackground, if (isNight()) NIGHT_SURFACE else DAY_SURFACE)

/** A card fill: the page lifted a few percent toward the text colour, so a card reads as a card. */
internal fun android.content.Context.panelColor(): Int = mixChannels(surfaceColor(),
    themeColor(android.R.attr.textColorPrimary, if (isNight()) NIGHT_TEXT else DAY_TEXT), PANEL_LIFT)

/** White or near-black, whichever is readable on the given fill. */
internal fun readableOn(background: Int): Int =
    if (luminance(background) > 0.45) DARK_ON_LIGHT else LIGHT_ON_DARK

/** Blending is done in plain channels, because a gradient of these colours is what a phone draws. */
internal fun mixChannels(from: Int, to: Int, fraction: Float): Int {
    fun blend(a: Int, b: Int) = (a + (b - a) * fraction).toInt().coerceIn(0, 255)
    return Color.argb(0xFF, blend(Color.red(from), Color.red(to)),
        blend(Color.green(from), Color.green(to)), blend(Color.blue(from), Color.blue(to)))
}

/** Relative luminance, the WCAG one. */
internal fun luminance(color: Int): Double {
    fun channel(value: Int): Double {
        val part = value / 255.0
        return if (part <= 0.03928) part / 12.92 else ((part + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) +
        0.0722 * channel(Color.blue(color))
}

internal fun contrastRatio(from: Int, to: Int): Double {
    val high = max(luminance(from), luminance(to))
    val low = min(luminance(from), luminance(to))
    return (high + 0.05) / (low + 0.05)
}

/** The theme's colour, moved toward black or toward white until it is readable as body text. Borders
 * keep the loud original; only words go through here. */
internal fun readableInk(color: Int, background: Int): Int {
    var ink = color
    repeat(12) {
        if (contrastRatio(ink, background) >= MIN_TEXT_CONTRAST) return@repeat
        ink = mixChannels(ink, if (luminance(background) > 0.45) Color.BLACK else Color.WHITE, 0.16f)
    }
    return ink
}

/** True when the system is asking for a dark palette; used only to pick a fallback colour. */
internal fun android.content.Context.isNight(): Boolean =
    resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

private const val DAY_SURFACE = 0xFFFAFAFA.toInt()
private const val NIGHT_SURFACE = 0xFF1E1F22.toInt()
private const val DAY_TEXT = 0xFF1B1B1B.toInt()
private const val NIGHT_TEXT = 0xFFE3E3E3.toInt()
private const val ACCENT = 0xFF1A73E8.toInt()
private const val ALERT = 0xFFB3261E.toInt()
private const val OUTLINE = 0xFF8A8F99.toInt()
private const val DARK_ON_LIGHT = 0xFF111111.toInt()
private const val LIGHT_ON_DARK = 0xFFFFFFFF.toInt()

/** How far a card panel moves toward the text colour, so it separates from the page without tinting. */
private const val PANEL_LIFT = 0.07f

/** Summed channel steps below which two colours are the same colour to an eye on a phone. */
private const val INDISTINGUISHABLE = 48

/** WCAG AA for body text. Nothing a person has to read sits below this. */
private const val MIN_TEXT_CONTRAST = 4.5
