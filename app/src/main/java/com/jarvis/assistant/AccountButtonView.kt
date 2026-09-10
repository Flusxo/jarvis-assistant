package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageButton

/** Small profile control that opens the JARVIS account center without requiring MainActivity changes. */
class AccountButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    init {
        setOnClickListener {
            context.startActivity(Intent(context, AccountActivity::class.java))
        }
        contentDescription = "JARVIS account"
        isClickable = true
        isFocusable = true
    }
}
