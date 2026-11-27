package de.dave.synchaven.data.api

import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.CompletedRequest
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.MoveTaskRequest
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.model.UpdateMeRequest
import okhttp3.RequestBody

/**
 * Forwards every call to the server ([remote]) or – in local mode without a server – to [local].
 * Sign-in and registration always go to the server.
 */
class SessionApi(
    private val remote: SyncHavenApi,
    private val local: SyncHavenApi,
    private val isLocal: () -> Boolean,
) : SyncHavenApi {
    private val api get() = if (isLocal()) local else remote

    override suspend fun info(server: String) = remote.info(server)
    override suspend fun login(server: String, body: LoginRequest) = remote.login(server, body)
    override suspend fun register(server: String, body: RegisterRequest) = remote.register(server, body)
    override suspend fun currentInfo() = api.currentInfo()
    override suspend fun me() = api.me()
    override suspend fun refreshToken() = api.refreshToken()
    override suspend fun updateMe(body: UpdateMeRequest) = api.updateMe(body)

    override suspend fun calendars() = api.calendars()
    override suspend fun createCalendar(body: CalendarInput) = api.createCalendar(body)
    override suspend fun updateCalendar(id: String, body: CalendarInput) = api.updateCalendar(id, body)
    override suspend fun deleteCalendar(id: String) = api.deleteCalendar(id)
    override suspend fun calendarEvents(id: String) = api.calendarEvents(id)
    override suspend fun exportCalendar(id: String) = api.exportCalendar(id)
    override suspend fun importCalendar(id: String, timezone: String, ics: RequestBody) = api.importCalendar(id, timezone, ics)

    override suspend fun occurrences(start: String, end: String, query: String?) = api.occurrences(start, end, query)
    override suspend fun event(id: String) = api.event(id)
    override suspend fun createEvent(body: EventInput) = api.createEvent(body)
    override suspend fun updateEvent(id: String, body: EventInput) = api.updateEvent(id, body)
    override suspend fun deleteEvent(id: String) = api.deleteEvent(id)
    override suspend fun addExdate(id: String, body: ExdateRequest) = api.addExdate(id, body)

    override suspend fun taskLists() = api.taskLists()
    override suspend fun createTaskList(body: TaskListInput) = api.createTaskList(body)
    override suspend fun updateTaskList(id: String, body: TaskListInput) = api.updateTaskList(id, body)
    override suspend fun deleteTaskList(id: String) = api.deleteTaskList(id)
    override suspend fun clearCompletedTasks(id: String) = api.clearCompletedTasks(id)
    override suspend fun tasks(listId: String?) = api.tasks(listId)
    override suspend fun task(id: String) = api.task(id)
    override suspend fun createTask(body: TaskInput) = api.createTask(body)
    override suspend fun updateTask(id: String, body: TaskInput) = api.updateTask(id, body)
    override suspend fun setTaskCompleted(id: String, body: CompletedRequest) = api.setTaskCompleted(id, body)
    override suspend fun deleteTask(id: String) = api.deleteTask(id)
    override suspend fun moveTask(id: String, body: MoveTaskRequest) = api.moveTask(id, body)

    override suspend fun addressBooks() = api.addressBooks()
    override suspend fun createAddressBook(body: AddressBookInput) = api.createAddressBook(body)
    override suspend fun updateAddressBook(id: String, body: AddressBookInput) = api.updateAddressBook(id, body)
    override suspend fun deleteAddressBook(id: String) = api.deleteAddressBook(id)
    override suspend fun exportAddressBook(id: String) = api.exportAddressBook(id)
    override suspend fun importAddressBook(id: String, vcf: RequestBody) = api.importAddressBook(id, vcf)
    override suspend fun contacts(addressBookId: String?) = api.contacts(addressBookId)
    override suspend fun contact(id: String) = api.contact(id)
    override suspend fun createContact(body: ContactInput) = api.createContact(body)
    override suspend fun updateContact(id: String, body: ContactInput) = api.updateContact(id, body)
    override suspend fun deleteContact(id: String) = api.deleteContact(id)
}
