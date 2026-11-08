package dev.yurlungr.app.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** Keyboards for fields where autocorrect/auto-capitalization would corrupt the value. */
object Keyboards {
    val Url = KeyboardOptions(
        keyboardType = KeyboardType.Uri,
        autoCorrectEnabled = false,
        capitalization = KeyboardCapitalization.None,
    )

    /** Folder/project names, hostnames. */
    val Identifier = KeyboardOptions(
        keyboardType = KeyboardType.Ascii,
        autoCorrectEnabled = false,
        capitalization = KeyboardCapitalization.None,
    )

    val Code = KeyboardOptions(
        keyboardType = KeyboardType.Ascii,
        autoCorrectEnabled = false,
        capitalization = KeyboardCapitalization.Characters,
    )

    val Email = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        autoCorrectEnabled = false,
        capitalization = KeyboardCapitalization.None,
    )
}
