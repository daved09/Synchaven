package de.dave.synchaven.data.api

import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.AuthResponse
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactImportResult
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.ExdateRequest
import de.dave.synchaven.data.model.ImportResult
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.UpdateMeRequest
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
 * REST API des SyncHaven-Servers. Pfade sind relativ zu `/api/`; der [ServerInterceptor] setzt
 * Schema, Host und Pfadpräfix des angemeldeten Servers ein. Vor der Anmeldung wird der Server
 * über den Header [SERVER_HEADER] übergeben.
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

    @PUT("auth/me")
    suspend fun updateMe(@Body body: UpdateMeRequest): User

    // ---------- Kalender ----------

    @GET("calendars")
    suspend fun calendars(): List<Calendar>

    @POST("calendars")
    suspend fun createCalendar(@Body body: CalendarInput): Calendar

    @PUT("calendars/{id}")
    suspend fun updateCalendar(@Path("id") id: String, @Body body: CalendarInput): Calendar

    @DELETE("calendars/{id}")
    suspend fun deleteCalendar(@Path("id") id: String)

    @GET("calendars/{id}/export")
    suspend fun exportCalendar(@Path("id") id: String): ResponseBody

    @POST("calendars/{id}/import")
    suspend fun importCalendar(
        @Path("id") id: String,
        @Query("timezone") timezone: String,
        @Body ics: RequestBody,
    ): ImportResult

    // ---------- Termine ----------

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

    // ---------- Adressbücher & Kontakte ----------

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
        /** Server für Aufrufe vor der Anmeldung; wird vom [ServerInterceptor] entfernt. */
        const val SERVER_HEADER = "X-SyncHaven-Server"
    }
}
