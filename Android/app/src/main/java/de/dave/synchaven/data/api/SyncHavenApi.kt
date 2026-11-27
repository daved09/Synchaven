package de.dave.synchaven.data.api

import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.AuthResponse
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.ClearCompletedResult
import de.dave.synchaven.data.model.CompletedRequest
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactImportResult
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.ImportResult
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.MoveTaskRequest
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.UpdateMeResponse
import de.dave.synchaven.data.model.User
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * REST API of the SyncHaven server. Paths are relative to `/api/`; the [ServerInterceptor] inserts
 * scheme, host and path prefix of the signed-in server. Before sign-in, the server is
 * passed via the [SERVER_HEADER] header.
 */
interface SyncHavenApi {
    @GET("info")
    suspend fun info(@Header(SERVER_HEADER) server: String): ServerInfo

    @POST("auth/login")
    suspend fun login(@Header(SERVER_HEADER) server: String, @Body body: LoginRequest): AuthResponse

    @POST("auth/register")
    suspend fun register(@Header(SERVER_HEADER) server: String, @Body body: RegisterRequest): AuthResponse

    @GET("info")
    suspend fun currentInfo(): ServerInfo

    @GET("auth/me")
    suspend fun me(): User

    /** New token for the signed-in session */
    @POST("auth/refresh")
    suspend fun refreshToken(): AuthResponse

    @PUT("auth/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): UpdateMeResponse

    // ---------- Calendars ----------

    @GET("calendars")
    suspend fun calendars(): List<Calendar>

    @POST("calendars")
    suspend fun createCalendar(@Body body: CalendarInput): Calendar

    @PUT("calendars/{id}")
    suspend fun updateCalendar(@Path("id") id: String, @Body body: CalendarInput): Calendar

    @DELETE("calendars/{id}")
    suspend fun deleteCalendar(@Path("id") id: String)

    /** All events of a calendar without expansion (series as one entry) – for syncing */
    @GET("calendars/{id}/events")
    suspend fun calendarEvents(@Path("id") id: String): List<CalEvent>

    @GET("calendars/{id}/export")
    suspend fun exportCalendar(@Path("id") id: String): ResponseBody

    @POST("calendars/{id}/import")
    suspend fun importCalendar(
        @Path("id") id: String,
        @Query("timezone") timezone: String,
        @Body ics: RequestBody,
    ): ImportResult

    // ---------- Events ----------

    @GET("events")
    suspend fun occurrences(
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("q") query: String? = null,
    ): List<Occurrence>

    @GET("events/{id}")
    suspend fun event(@Path("id") id: String): CalEvent

    @POST("events")
    suspend fun createEvent(@Body body: EventInput): CalEvent

    @PUT("events/{id}")
    suspend fun updateEvent(@Path("id") id: String, @Body body: EventInput): CalEvent

    @DELETE("events/{id}")
    suspend fun deleteEvent(@Path("id") id: String)

    @POST("events/{id}/exdates")
    suspend fun addExdate(@Path("id") id: String, @Body body: ExdateRequest): CalEvent

    // ---------- Tasks ----------

    /** Creates the default list ("My tasks") if needed */
    @GET("task-lists")
    suspend fun taskLists(): List<TaskList>

    @POST("task-lists")
    suspend fun createTaskList(@Body body: TaskListInput): TaskList

    @PUT("task-lists/{id}")
    suspend fun updateTaskList(@Path("id") id: String, @Body body: TaskListInput): TaskList

    /** Deletes all contained tasks; the last list cannot be deleted (400) */
    @DELETE("task-lists/{id}")
    suspend fun deleteTaskList(@Path("id") id: String)

    @POST("task-lists/{id}/clear-completed")
    suspend fun clearCompletedTasks(@Path("id") id: String): ClearCompletedResult

    @GET("tasks")
    suspend fun tasks(@Query("list_id") listId: String? = null): List<Task>

    @GET("tasks/{id}")
    suspend fun task(@Path("id") id: String): Task

    @POST("tasks")
    suspend fun createTask(@Body body: TaskInput): Task

    @PUT("tasks/{id}")
    suspend fun updateTask(@Path("id") id: String, @Body body: TaskInput): Task

    @PUT("tasks/{id}/completed")
    suspend fun setTaskCompleted(@Path("id") id: String, @Body body: CompletedRequest): Task

    @DELETE("tasks/{id}")
    suspend fun deleteTask(@Path("id") id: String)

    /** Own order; returns all tasks of that level with their new positions */
    @PUT("tasks/{id}/position")
    suspend fun moveTask(@Path("id") id: String, @Body body: MoveTaskRequest): List<Task>

    // ---------- Address books & contacts ----------

    @GET("addressbooks")
    suspend fun addressBooks(): List<AddressBook>

    @POST("addressbooks")
    suspend fun createAddressBook(@Body body: AddressBookInput): AddressBook

    @PUT("addressbooks/{id}")
    suspend fun updateAddressBook(@Path("id") id: String, @Body body: AddressBookInput): AddressBook

    @DELETE("addressbooks/{id}")
    suspend fun deleteAddressBook(@Path("id") id: String)

    @GET("addressbooks/{id}/export")
    suspend fun exportAddressBook(@Path("id") id: String): ResponseBody

    @POST("addressbooks/{id}/import")
    suspend fun importAddressBook(@Path("id") id: String, @Body vcf: RequestBody): ContactImportResult

    @GET("contacts")
    suspend fun contacts(@Query("addressbook_id") addressBookId: String? = null): List<Contact>

    @GET("contacts/{id}")
    suspend fun contact(@Path("id") id: String): Contact

    @POST("contacts")
    suspend fun createContact(@Body body: ContactInput): Contact

    @PUT("contacts/{id}")
    suspend fun updateContact(@Path("id") id: String, @Body body: ContactInput): Contact

    @DELETE("contacts/{id}")
    suspend fun deleteContact(@Path("id") id: String)

    companion object {
        /** Server for calls before sign-in; removed by the [ServerInterceptor]. */
        const val SERVER_HEADER = "X-SyncHaven-Server"
    }
}
