package com.fantadrom.copperscroll.storage.cards

import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.crypto.*
import java.util.Collections

object CardLimits {
    const val BODY = 4 * Limits.CHUNK
    const val FIELDS = 256
    const val TEXT_POINTS = 32768
    const val TEXT_BYTES = 131072
    const val DRAFT_BYTES = 16 * Limits.CHUNK
}
sealed interface FieldParameters
object NoParameters : FieldParameters
class TextParameters(val mask: String? = null, val digitsOnly: Boolean = false,
                     val uppercaseOnly: Boolean = false, val maxLength: Int = CardLimits.TEXT_POINTS) : FieldParameters {
    override fun toString() = "TextParameters(redacted)"
}
class DateParameters(val mask: String = "dd.mm.yyyy") : FieldParameters {
    override fun toString() = "DateParameters(redacted)"
}
class PatternParameters(val side: Int = 3) : FieldParameters
sealed interface CardValue
class TextValue(val text: String = "") : CardValue { override fun toString() = "TextValue(redacted)" }
class CardDate(val year: Int, val month: Int, val day: Int) {
    override fun toString() = "CardDate(redacted)"
}
class DateValue(val date: CardDate? = null) : CardValue { override fun toString() = "DateValue(redacted)" }
class PatternValue(points: List<Int> = emptyList()) : CardValue {
    init { checkFormat(points.size <= 36,Failure.UnsupportedLimit) }
    val points: List<Int> = Collections.unmodifiableList(ArrayList(points))
    override fun toString() = "PatternValue(redacted)"
}
class IntegerValue(val integer: String = "0") : CardValue { override fun toString() = "IntegerValue(redacted)" }
class DecimalValue(val unscaled: String = "0", val scale: Int = 0) : CardValue {
    override fun toString() = "DecimalValue(redacted)"
}
class BooleanValue(val enabled: Boolean = false) : CardValue { override fun toString() = "BooleanValue(redacted)" }
class CardField(val id: Bytes, val type: Int, val name: String, val parameters: FieldParameters,
                val value: CardValue) {
    fun renamed(name: String) = CardField(id,type,name,parameters,value)
    fun withValue(value: CardValue) = CardField(id,type,name,parameters,value)
    fun withParameters(parameters: FieldParameters) = CardField(id,type,name,parameters,value)
    override fun toString() = "CardField(redacted)"
    companion object {
        fun create(type: Int, name: String = "", parameters: FieldParameters? = null): CardField {
            val p = parameters ?: when(type) { 1,3 -> TextParameters(); 6 -> DateParameters(); 7 -> PatternParameters(); else -> NoParameters }
            val v: CardValue = when(type) {
                in 1..5 -> TextValue(); 6 -> DateValue(); 7 -> PatternValue(); 8 -> IntegerValue()
                9 -> DecimalValue(); 10 -> BooleanValue(); else -> throw FormatException(Failure.UnsupportedVersion)
            }
            return CardField(Bytes(SystemEntropy().bytes(16,true)),type,name,p,v)
        }
    }
}
/** Collections are frozen at admission. No data-class diagnostics expose contents. */
class CardBody(fields: List<CardField> = emptyList()) {
    init {
        checkFormat(fields.size <= CardLimits.FIELDS,Failure.UnsupportedLimit)
        var units = 0L
        for(f in fields) {
            checkFormat(f.name.length <= 2048,Failure.ResourceLimit); units += f.name.length
            when(val v = f.value) {
                is TextValue -> { checkFormat(v.text.length <= CardLimits.TEXT_BYTES,Failure.ResourceLimit); units += v.text.length }
                is IntegerValue -> checkFormat(v.integer.length <= 128,Failure.ResourceLimit)
                is DecimalValue -> checkFormat(v.unscaled.length <= 128,Failure.ResourceLimit)
                else -> Unit
            }
            when(val p = f.parameters) {
                is TextParameters -> { checkFormat((p.mask?.length ?: 0) <= 1024,Failure.ResourceLimit); units += p.mask?.length ?: 0 }
                is DateParameters -> { checkFormat(p.mask.length <= 256,Failure.ResourceLimit); units += p.mask.length }
                else -> Unit
            }
            checkFormat(units*2 <= CardLimits.DRAFT_BYTES,Failure.ResourceLimit)
        }
    }
    val fields: List<CardField> = Collections.unmodifiableList(ArrayList(fields))
    fun add(field: CardField): CardBody {
        checkFormat(fields.none { it.id == field.id },Failure.ValidationError)
        return CardBody(fields + field).also { CardCodec.requireValid(it) }
    }
    fun remove(id: Bytes) = CardBody(fields.filter { it.id != id })
    fun replace(field: CardField): CardBody {
        val previous = fields.singleOrNull { it.id == field.id } ?: throw FormatException(Failure.ValidationError)
        checkFormat(previous.type == field.type,Failure.ValidationError)
        return CardBody(fields.map { if(it.id == field.id) field else it }).also { CardCodec.requireValid(it) }
    }
    fun reorder(ids: List<Bytes>): CardBody {
        checkFormat(ids.size == fields.size && ids.toSet().size == ids.size && ids.toSet() == fields.map { it.id }.toSet(),Failure.ValidationError)
        val byId = fields.associateBy { it.id }; return CardBody(ids.map { byId.getValue(it) })
    }
    override fun toString() = "CardBody(redacted)"
}
class CardIssue(val fieldId: Bytes?, val code: String) { override fun toString() = "CardIssue(code=$code)" }
class CardValidation(errors: List<CardIssue>, warnings: List<CardIssue>) {
    val errors: List<CardIssue> = Collections.unmodifiableList(ArrayList(errors))
    val warnings: List<CardIssue> = Collections.unmodifiableList(ArrayList(warnings))
    val valid get() = errors.isEmpty()
    override fun toString() = "CardValidation(valid=$valid)"
}
