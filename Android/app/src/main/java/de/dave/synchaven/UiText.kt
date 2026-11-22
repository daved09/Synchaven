package de.dave.synchaven

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text für die UI, der erst dort aufgelöst wird, wo Ressourcen verfügbar sind (in Compose).
 * So brauchen ViewModels, Datenschicht und Hilfslogik keinen Context und bleiben ohne Android testbar.
 */
sealed interface UiText {
    /** Fertiger Text, z. B. eine Fehlermeldung des Servers oder ein Name */
    data class Plain(val value: String) : UiText

    /** String-Ressource mit optionalen Formatargumenten (auch verschachtelte [UiText]) */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** Plural-Ressource; [args] sind die vollständigen Formatargumente (meist beginnend mit [count]) */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Mehrere Texte, verbunden mit dem Trennzeichen aus [separator] */
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
