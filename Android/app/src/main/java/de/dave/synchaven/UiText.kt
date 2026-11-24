package de.dave.synchaven

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text for the UI that is only resolved where resources are available (in Compose).
 * This way ViewModels, the data layer and helper logic need no Context and stay testable without Android.
 */
sealed interface UiText {
    /** Ready-made text, e.g. an error message from the server or a name */
    data class Plain(val value: String) : UiText

    /** String resource with optional format arguments (also nested [UiText]) */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Plural resource; [args] are the complete format arguments (usually starting with [count]) */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Several texts, joined with the separator from [separator] */
    data class Join(val parts: List<UiText>, @StringRes val separator: Int = R.string.list_separator) : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> resources.getString(id, *resolveArgs(args, resources))
        is Plural -> resources.getQuantityString(id, count, *resolveArgs(args, resources))
        is Join -> parts.joinToString(resources.getString(separator)) { it.resolve(resources) }
    }

    @Composable
    fun asString(): String = resolve(LocalResources.current)
}

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun pluralText(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

fun String.toUiText(): UiText = UiText.Plain(this)
