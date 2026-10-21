package com.fantadrom.copperscroll.storage.cards

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.io.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import java.util.Collections

class CardDraft(val title: String, val body: CardBody, val revision: Long,
                val color: Long = 0xffffffffL, val icon: String = "card") {
    override fun toString() = "CardDraft(redacted)"
}
class CardSave internal constructor(val token: OperationToken, internal val draftRevision: Long,
    internal val requestRevision: Long, internal val previousObjectRevision: ULong?) {
    override fun toString() = "CardSave(redacted)"
}
/** Process-memory notice only. No login-screen API or plaintext persistent flag. */
class ZoneNoticeMailbox {
    private val pending = LinkedHashMap<Pair<Bytes,Bytes>,Any>()
    @Synchronized internal fun mark(identity: Pair<Bytes,Bytes>): Any {
        if(identity !in pending && pending.size == 256) pending.remove(pending.keys.first())
        return Any().also { pending[identity] = it }
    }
    @Synchronized internal fun resolve(identity: Pair<Bytes,Bytes>,ticket: Any) { if(pending[identity] === ticket) pending.remove(identity) }
    fun consumeAfterAuthenticatedZoneOpen(repository: ZoneRepository): Boolean {
        val identity=repository.authenticatedIdentity() // Requires a live authenticated key/owner, not public login IDs.
        return synchronized(this) { pending.remove(identity) != null }
    }
}
/** Headless draft state. All actual IO is explicit and runs outside this monitor. */
class CardDraftSession(private val repository: ZoneRepository, val id: Bytes, private val parent: Bytes,
                       initial: StoredCard? = null, private val notices: ZoneNoticeMailbox = ZoneNoticeMailbox()) {
    private val identity = repository.authenticatedIdentity()
    private var current: CardDraft? = if(initial == null) CardDraft("",CardBody(),0) else
        CardDraft(initial.metadata.name,initial.body,0,initial.metadata.colorArgb,initial.metadata.icon)
    private var valid: CardDraft? = current?.takeIf { validate(it).valid }
    private var acknowledged = 0L
    private var objectRevision: ULong? = initial?.metadata?.revision
    private var hasSaved = initial != null
    private var closed = false
    private var closedRevision = 0L
    private var notice: Any? = null
    private val revealed = HashSet<Bytes>()
    private val saves = HashSet<CardSave>()
    init { checkFormat(initial == null || initial.metadata.id == id && initial.metadata.parent == parent,Failure.ValidationError) }
    @Synchronized fun snapshot(): CardDraft { requireIo(!closed,IoCode.AccessLost); return current!! }
    private fun validate(d: CardDraft): CardValidation {
        val result = CardCodec.validate(d.title,d.body)
        val metadata = d.color !in 0..0xffffffffL || !d.icon.matches(Regex("[a-z][a-z0-9_-]{0,31}"))
        return if(metadata) CardValidation(result.errors+CardIssue(null,"Metadata"),result.warnings) else result
    }
    @Synchronized fun validation(): CardValidation = validate(snapshot())
    @Synchronized fun edit(title: String, body: CardBody, color: Long = snapshot().color, icon: String = snapshot().icon): CardDraft {
        requireIo(!closed,IoCode.AccessLost); val old = current!!; checkFormat(old.revision < Long.MAX_VALUE,Failure.UnsupportedLimit)
        checkFormat(title.length <= 4096 && icon.length <= 128,Failure.ResourceLimit)
        // Body/field/parameter/value collections were frozen by their constructors.
        val next = CardDraft(title,CardBody(body.fields),old.revision+1,color,icon); current = next
        if(validate(next).valid) valid = next
        revealed.retainAll(next.body.fields.filter { it.type == 3 }.map { it.id }.toSet()); return next
    }
    val dirty: Boolean @Synchronized get() = if(closed) closedRevision > acknowledged else current!!.revision > acknowledged
    val saved: Boolean @Synchronized get() = hasSaved && !closed && !dirty
    /** Must be called BEFORE revoke; a late callback cannot create a new checkpoint. */
    @Synchronized fun prepareSave(lastWhollyValid: Boolean = false): CardSave? {
        requireIo(!closed,IoCode.AccessLost)
        val d = (if(lastWhollyValid) valid else current?.takeIf { validate(it).valid }) ?: return null
        if(hasSaved && d.revision <= acknowledged) return null
        if(saves.any { it.draftRevision == d.revision }) return saves.single { it.draftRevision == d.revision }
        requireIo(saves.size < 16,IoCode.Busy)
        val stamp = repository.stamp(); val requestRevision = maxOf(stamp.nextRevision,d.revision)
        val token = repository.issueSaveCard(stamp,id,parent,d.title,d.body,objectRevision,d.color,d.icon,requestRevision)
        return CardSave(token,d.revision,requestRevision,objectRevision).also { saves.add(it) }
    }
    fun submit(save: CardSave): WriteOutcome {
        synchronized(this) { checkFormat(save in saves,Failure.ValidationError) }
        val outcome = repository.submit(save.token); acknowledge(save,outcome)
        if(outcome.code == WriteCode.CommitUnknown) revoke()
        return outcome
    }
    fun reconcile(save: CardSave): WriteOutcome {
        synchronized(this) { checkFormat(save in saves,Failure.ValidationError) }
        val outcome = repository.reconcile(); acknowledge(save,outcome); return outcome
    }
    @Synchronized private fun acknowledge(save: CardSave,result: WriteOutcome) {
        if(result.code == WriteCode.Committed || result.code == WriteCode.NoOp) {
            checkFormat(result.revision == save.requestRevision && result.objectId == id)
            acknowledged = maxOf(acknowledged,save.draftRevision); hasSaved = true
            if(result.code == WriteCode.Committed) objectRevision = (save.previousObjectRevision ?: 0u)+1u
            if(closed && closedRevision <= acknowledged) notice?.let { notices.resolve(identity,it) }
        }
        if(result.code !in listOf(WriteCode.CommitUnknown,WriteCode.Busy)) saves.remove(save)
    }
    @Synchronized fun reveal(fieldId: Bytes,show: Boolean) {
        val d = snapshot(); checkFormat(d.body.fields.any { it.id == fieldId && it.type == 3 },Failure.ValidationError)
        if(show) revealed.add(fieldId) else revealed.remove(fieldId)
    }
    @Synchronized fun display(fieldId: Bytes): String {
        val field = snapshot().body.fields.singleOrNull { it.id == fieldId } ?: throw FormatException(Failure.ValidationError)
        if(field.type == 3 && fieldId !in revealed) return "●●●●●"
        return displayValue(field)
    }
    fun copyDisplay(fieldId: Bytes): String {
        requireIo(!repository.settings().copyBlocked,IoCode.AccessLost)
        return synchronized(this) {
            val field = snapshot().body.fields.singleOrNull { it.id == fieldId } ?: throw FormatException(Failure.ValidationError)
            requireIo(field.type != 3 || fieldId in revealed,IoCode.AccessLost); displayValue(field)
        }
    }
    /** Fast boundary: no core mutex/IO. The optional checkpoint already exists and is immutable. */
    @Synchronized fun revoke(preaccepted: CardSave? = null) {
        if(closed) return
        checkFormat(preaccepted == null || preaccepted in saves,Failure.ValidationError)
        closedRevision = current!!.revision
        if(dirty) notice = notices.mark(identity)
        revealed.clear(); current = null; valid = null; closed = true
        repository.core.revokeForClosingSave(preaccepted?.token)
    }
    private fun displayValue(field: CardField): String = when(val v = field.value) {
        is TextValue -> (field.parameters as? TextParameters)?.mask?.let { DigitMask.parse(it).format(v.text) } ?: v.text
        is DateValue -> DateMask.parse((field.parameters as DateParameters).mask).format(v.date)
        is PatternValue -> v.points.joinToString("-"); is IntegerValue -> v.integer
        is DecimalValue -> ExactNumbers.format(v); is BooleanValue -> v.enabled.toString()
    }
    override fun toString() = "CardDraftSession(redacted)"
}
interface AppMessagePreferences { var cardEditorWarningSuppressed: Boolean }
class MemoryAppMessagePreferences : AppMessagePreferences { override var cardEditorWarningSuppressed = false }
enum class AppMessage { CardEditorWarning, UnsavedChanges, Confirmation }
class CardMessagePolicy(private val preferences: AppMessagePreferences) {
    fun shouldShow(message: AppMessage): Boolean = message != AppMessage.CardEditorWarning || !preferences.cardEditorWarningSuppressed
    fun dismissCardEditorWarning() { preferences.cardEditorWarningSuppressed = true }
    fun restoreHiddenSystemMessages() { preferences.cardEditorWarningSuppressed = false }
    companion object {
        const val WARNING = "Ввод в некоторые поля проверяется на корректность. Сохранение карточки доступно только если нет критических ошибок при вводе. При Home или блокировке телефона сессия закрывается; изменения после последнего успешного сохранения могут быть потеряны. Ранее сохранённые данные останутся без изменений."
    }
}
class BuiltInField(val type: Int, val name: String, val parameters: FieldParameters? = null)
class BuiltInCardTemplate(definitions: List<BuiltInField>) {
    init { checkFormat(definitions.size <= CardLimits.FIELDS,Failure.UnsupportedLimit) }
    private val definitions = Collections.unmodifiableList(ArrayList(definitions))
    fun instantiate(): CardBody = CardBody(definitions.map { CardField.create(it.type,it.name,it.parameters) })
}
object CardTemplates {
    val empty = BuiltInCardTemplate(emptyList())
    val bankCard = BuiltInCardTemplate(listOf(BuiltInField(1,"Название банка"),BuiltInField(1,"Платежная система"),
        BuiltInField(1,"Номер карты",TextParameters("0000 0000 0000 0000")),BuiltInField(6,"Срок действия",DateParameters("mm/yy")),
        BuiltInField(3,"CVV",TextParameters(digitsOnly=true)),BuiltInField(2,"Примечание")))
}
