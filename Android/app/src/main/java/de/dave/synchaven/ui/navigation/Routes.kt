package de.dave.synchaven.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object CalendarRoute

@Serializable
data object ContactsRoute

@Serializable
data object SettingsRoute

/** Task lists with quick entry */
@Serializable
data object TasksRoute

/** New event (without [eventId]) from [startEpochSecond] or editing an existing event/series. */
@Serializable
data class EventEditRoute(
    val eventId: String? = null,
    val startEpochSecond: Long? = null,
    val allDay: Boolean = false,
)

@Serializable
data class ContactDetailRoute(val id: String)

@Serializable
data class ContactEditRoute(val id: String? = null, val addressBookId: String? = null, val category: String? = null)
