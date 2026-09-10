package com.jarvis.assistant

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Small dashboard shortcut. It reuses MainActivity's existing input/send controls,
 * so shortcuts stay connected to the same command parser instead of duplicating logic.
 */
class JarvisQuickActionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val root = rootView
            val input = root.findViewById<EditText>(R.id.inputText) ?: return@setOnClickListener
            val command = when (tag?.toString()?.uppercase()) {
                "MIC" -> null
                "VISION" -> "What am I looking at?"
                "MUSIC" -> "play music on Spotify"
                "TIMER" -> "set a timer for 5 minutes"
                "NOTE" -> "note that "
                "MAP" -> "show me a map of Paris"
                else -> null
            }

            if (tag?.toString()?.uppercase() == "MIC") {
                root.findViewById<View>(R.id.micButton)?.performClick()
                return@setOnClickListener
            }

            if (command != null) {
                input.setText(command)
                input.setSelection(input.text.length)
                input.requestFocus()
            }
        }
    }
}
