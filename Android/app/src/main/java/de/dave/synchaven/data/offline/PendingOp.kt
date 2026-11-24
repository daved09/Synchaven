package de.dave.synchaven.data.offline

import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskListInput
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Prefix of temporary IDs of events, contacts and tasks that were created offline and not yet transferred. */
const val LOCAL_ID_PREFIX = "local-"

fun newLocalId(): String = LOCAL_ID_PREFIX + UUID.randomUUID()

fun isLocalId(id: String) = id.startsWith(LOCAL_ID_PREFIX)

/**
 * Change made offline that is transferred on the next contact with the server. The queue
 * is processed in order; [enqueue] coalesces changes to the same object.
 */
@Serializable
sealed interface PendingOp {
    /** ID of the affected event, contact or calendar */
    val targetId: String

    @Serializable @SerialName("event_create")
    data class CreateEvent(val localId: String, val input: EventInput) : PendingOp {
        override val targetId get() = localId
    }

    @Serializable @SerialName("event_update")
    data class UpdateEvent(val id: String, val input: EventInput) : PendingOp {
        override val targetId get() = id
    }

    @Serializable @SerialName("event_delete")
    data class DeleteEvent(val id: String) : PendingOp {
        override val targetId get() = id
    }

    /** An occurrence of a series is removed */
    @Serializable @SerialName("event_exdate")
    data class AddExdate(val id: String, val occurrenceStart: String) : PendingOp {
        override val targetId get() = id
    }

    @Serializable @SerialName("contact_create")
    data class CreateContact(val localId: String, val input: ContactInput) : PendingOp {
        override val targetId get() = localId
    }

    @Serializable @SerialName("contact_update")
    data class UpdateContact(val id: String, val input: ContactInput) : PendingOp {
        override val targetId get() = id
    }

    @Serializable @SerialName("contact_delete")
    data class DeleteContact(val id: String) : PendingOp {
        override val targetId get() = id
    }

    /** Name, color or visibility of a calendar */
    @Serializable @SerialName("calendar_update")
    data class UpdateCalendar(val id: String, val input: CalendarInput) : PendingOp {
        override val targetId get() = id
    }

    /** [createdAt]: creation time, so the task is sorted correctly until it is transferred */
    @Serializable @SerialName("task_create")
    data class CreateTask(val localId: String, val input: TaskInput, val createdAt: String = "") : PendingOp {
        override val targetId get() = localId
    }

    @Serializable @SerialName("task_update")
    data class UpdateTask(val id: String, val input: TaskInput) : PendingOp {
        override val targetId get() = id
    }

    /** Check off or reopen; [at] is the time of checking off (until the server provides its own) */
    @Serializable @SerialName("task_completed")
    data class SetTaskCompleted(val id: String, val completed: Boolean, val at: String = "") : PendingOp {
        override val targetId get() = id
    }

    @Serializable @SerialName("task_delete")
    data class DeleteTask(val id: String) : PendingOp {
        override val targetId get() = id
    }

    /** Name, color or visibility of a task list */
    @Serializable @SerialName("task_list_update")
    data class UpdateTaskList(val id: String, val input: TaskListInput) : PendingOp {
        override val targetId get() = id
    }

    /** The calendar's default view in the user account */
    @Serializable @SerialName("default_view")
    data class SetDefaultView(val view: String) : PendingOp {
        override val targetId get() = ""
    }
}

private fun PendingOp.isEventOp() =
    this is PendingOp.CreateEvent || this is PendingOp.UpdateEvent || this is PendingOp.DeleteEvent || this is PendingOp.AddExdate

private fun PendingOp.isContactOp() =
    this is PendingOp.CreateContact || this is PendingOp.UpdateContact || this is PendingOp.DeleteContact

private fun PendingOp.isTaskOp() =
    this is PendingOp.CreateTask || this is PendingOp.UpdateTask || this is PendingOp.SetTaskCompleted || this is PendingOp.DeleteTask

private fun PendingOp.sameTarget(other: PendingOp) = targetId == other.targetId &&
    ((isEventOp() && other.isEventOp()) || (isContactOp() && other.isContactOp()) || (isTaskOp() && other.isTaskOp()))

/**
 * Appends [op] to the queue and coalesces it with earlier changes to the same object:
 * editing an object created offline changes its creation, deleting removes it entirely, a newer
 * edit replaces the older one. The first [frozen] entries are currently being transferred and stay unchanged.
 */
fun List<PendingOp>.enqueue(op: PendingOp, frozen: Int = 0): List<PendingOp> {
    val head = take(frozen)
    val tail = drop(frozen)
    return head + tail.merge(op)
}

private fun List<PendingOp>.merge(op: PendingOp): List<PendingOp> = when (op) {
    is PendingOp.CreateEvent, is PendingOp.CreateContact, is PendingOp.CreateTask -> this + op

    is PendingOp.UpdateEvent -> {
        val create = firstOrNull { it is PendingOp.CreateEvent && it.localId == op.id }
        if (create != null) map { if (it === create) PendingOp.CreateEvent(op.id, op.input) else it }
        else filterNot { it is PendingOp.UpdateEvent && it.id == op.id } + op
    }

    is PendingOp.AddExdate -> {
        fun EventInput.withExdate() = if (op.occurrenceStart in exdates) this else copy(exdates = exdates + op.occurrenceStart)
        val target = lastOrNull {
            (it is PendingOp.CreateEvent && it.localId == op.id) || (it is PendingOp.UpdateEvent && it.id == op.id)
        }
        when (target) {
            is PendingOp.CreateEvent -> map { if (it === target) target.copy(input = target.input.withExdate()) else it }
            // The edit replaces all EXDATEs anyway when transferred
            is PendingOp.UpdateEvent -> map { if (it === target) target.copy(input = target.input.withExdate()) else it }
            else -> this + op
        }
    }

    is PendingOp.UpdateContact -> {
        val create = firstOrNull { it is PendingOp.CreateContact && it.localId == op.id } as PendingOp.CreateContact?
        if (create != null) {
            // Photo: `null` means "unchanged" – the photo of the creation is then kept
            val input = if (op.input.photo == null) op.input.copy(photo = create.input.photo) else op.input
            map { if (it === create) PendingOp.CreateContact(op.id, input) else it }
        } else {
            val previous = lastOrNull { it is PendingOp.UpdateContact && it.id == op.id } as PendingOp.UpdateContact?
            val input = if (op.input.photo == null && previous != null) op.input.copy(photo = previous.input.photo) else op.input
            filterNot { it is PendingOp.UpdateContact && it.id == op.id } + PendingOp.UpdateContact(op.id, input)
        }
    }

    is PendingOp.UpdateTask -> {
        val create = firstOrNull { it is PendingOp.CreateTask && it.localId == op.id } as PendingOp.CreateTask?
        // A queued check-off is kept if the edit doesn't set the status
        if (create != null) map { if (it === create) create.copy(input = op.input.copy(completed = op.input.completed ?: create.input.completed)) else it }
        else filterNot { it is PendingOp.UpdateTask && it.id == op.id } + op
    }

    is PendingOp.SetTaskCompleted -> {
        val create = firstOrNull { it is PendingOp.CreateTask && it.localId == op.id } as PendingOp.CreateTask?
        if (create != null) map { if (it === create) create.copy(input = create.input.copy(completed = op.completed)) else it }
        else filterNot { it is PendingOp.SetTaskCompleted && it.id == op.id } + op
    }

    is PendingOp.DeleteEvent, is PendingOp.DeleteContact, is PendingOp.DeleteTask -> {
        val createdOffline = any {
            (it is PendingOp.CreateEvent && op is PendingOp.DeleteEvent && it.localId == op.targetId) ||
                (it is PendingOp.CreateContact && op is PendingOp.DeleteContact && it.localId == op.targetId) ||
                (it is PendingOp.CreateTask && op is PendingOp.DeleteTask && it.localId == op.targetId)
        }
        val rest = filterNot { it.sameTarget(op) }
        if (createdOffline) rest else rest + op
    }

    is PendingOp.UpdateCalendar -> filterNot { it is PendingOp.UpdateCalendar && it.id == op.id } + op

    is PendingOp.UpdateTaskList -> filterNot { it is PendingOp.UpdateTaskList && it.id == op.id } + op

    is PendingOp.SetDefaultView -> filterNot { it is PendingOp.SetDefaultView } + op
}

/** After a creation has been transferred, replaces the temporary ID in the remaining changes. */
fun List<PendingOp>.remap(localId: String, serverId: String): List<PendingOp> = map { op ->
    when {
        // Subtasks created offline under a parent task created offline
        op is PendingOp.CreateTask && op.input.parentId == localId -> op.copy(input = op.input.copy(parentId = serverId))
        op is PendingOp.UpdateTask && op.input.parentId == localId ->
            op.copy(id = if (op.id == localId) serverId else op.id, input = op.input.copy(parentId = serverId))
        op.targetId != localId -> op
        op is PendingOp.UpdateEvent -> op.copy(id = serverId)
        op is PendingOp.DeleteEvent -> op.copy(id = serverId)
        op is PendingOp.AddExdate -> op.copy(id = serverId)
        op is PendingOp.UpdateContact -> op.copy(id = serverId)
        op is PendingOp.DeleteContact -> op.copy(id = serverId)
        op is PendingOp.UpdateTask -> op.copy(id = serverId)
        op is PendingOp.SetTaskCompleted -> op.copy(id = serverId)
        op is PendingOp.DeleteTask -> op.copy(id = serverId)
        else -> op
    }
}
