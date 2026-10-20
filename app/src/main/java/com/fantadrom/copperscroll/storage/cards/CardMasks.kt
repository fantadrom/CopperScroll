package com.fantadrom.copperscroll.storage.cards

import com.fantadrom.copperscroll.storage.format.*
import java.time.LocalDate
import com.ibm.icu.lang.UCharacter

class DigitMask private constructor(private val pattern: String, val slots: Int) {
    fun validateRaw(raw: String) { checkFormat(raw.length <= slots && raw.all { it in '0'..'9' },Failure.ValidationError) }
    fun format(raw: String): String {
        validateRaw(raw); var i = 0
        return buildString(pattern.length) { for(c in pattern) append(if(c == '0') raw.getOrNull(i++) ?: ' ' else c) }
    }
    /** Filtering/truncation is the explicit paste rule, not a general value conversion. */
    fun paste(raw: String, clipboard: String, rawOffset: Int = raw.length): String {
        validateRaw(raw); checkFormat(rawOffset in 0..raw.length,Failure.ValidationError)
        checkFormat(clipboard.length <= CardLimits.TEXT_BYTES,Failure.UnsupportedLimit)
        val accepted = buildString(slots-raw.length) {
            for(c in clipboard) { if(length == slots-raw.length) break; if(c in '0'..'9') append(c) }
        }
        return raw.substring(0,rawOffset)+accepted+raw.substring(rawOffset)
    }
    fun delete(raw: String, from: Int, until: Int): String {
        validateRaw(raw); checkFormat(from in 0..until && until <= raw.length,Failure.ValidationError)
        return raw.removeRange(from,until)
    }
    override fun toString() = "DigitMask(redacted)"
    companion object {
        fun parse(mask: String): DigitMask {
            checkFormat(mask.length in 1..256 && validUtf16(mask),Failure.ValidationError)
            var slots = 0
            for(c in mask) {
                if(c == '0') slots++ else checkFormat(c == ' ' ||
                    (c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126) && c != '\\' ||
                    c in "±×÷−≤≥«»“”‘’",Failure.ValidationError)
            }
            checkFormat(slots in 1..128,Failure.ValidationError); return DigitMask(mask,slots)
        }
    }
}
class DateMask private constructor(private val tokens: List<String>) {
    fun format(date: CardDate?): String {
        if(date == null) return ""
        validateDate(date)
        return tokens.joinToString("") { when(it) {
            "dd" -> date.day.toString().padStart(2,'0'); "mm" -> date.month.toString().padStart(2,'0')
            "yy" -> (date.year%100).toString().padStart(2,'0'); "yyyy" -> date.year.toString().padStart(4,'0'); else -> it
        } }
    }
    override fun toString() = "DateMask(redacted)"
    companion object {
        fun parse(mask: String): DateMask {
            checkFormat(mask.length in 1..64,Failure.ValidationError)
            var p = 0; val tokens = ArrayList<String>(); val components = HashSet<String>()
            while(p < mask.length) {
                val token = listOf("yyyy","yy","dd","mm").firstOrNull { mask.startsWith(it,p) }
                if(token != null) {
                    checkFormat(components.add(if(token.startsWith('y')) "year" else token),Failure.ValidationError)
                    tokens.add(token); p += token.length
                } else {
                    checkFormat(mask[p] in ".-/ ",Failure.ValidationError); tokens.add(mask[p++].toString())
                }
            }
            checkFormat(components.isNotEmpty(),Failure.ValidationError); return DateMask(tokens)
        }
        fun validateDate(date: CardDate) {
            checkFormat(date.year in 1..9999,Failure.ValidationError)
            try { LocalDate.of(date.year,date.month,date.day) }
            catch(_: java.time.DateTimeException) { throw FormatException(Failure.ValidationError) }
        }
        fun initialPickerDate() = CardDate(2000,1,1) // Never assigned to an empty value automatically.
    }
}
object ExactNumbers {
    fun validateInteger(value: String) {
        checkFormat(value.length in 1..39 && value.matches(Regex("0|-?[1-9][0-9]{0,37}")),Failure.ValidationError)
    }
    fun parseInteger(text: String): IntegerValue { validateInteger(text); return IntegerValue(text) }
    fun parseDecimal(text: String): DecimalValue {
        checkFormat(text.length in 1..60 && text.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]{1,18})?")),Failure.ValidationError)
        val scale = if('.' in text) text.length-text.indexOf('.')-1 else 0
        val negative = text.startsWith('-'); val digits = text.removePrefix("-").replace(".","").trimStart('0').ifEmpty { "0" }
        val unscaled = if(negative && digits != "0") "-$digits" else digits
        validateInteger(unscaled); return DecimalValue(unscaled,scale)
    }
    fun format(value: DecimalValue): String {
        validateInteger(value.unscaled); checkFormat(value.scale in 0..18,Failure.ValidationError)
        if(value.scale == 0) return value.unscaled
        val digits = value.unscaled.removePrefix("-").padStart(value.scale+1,'0')
        return (if(value.unscaled.startsWith('-')) "-" else "")+digits.dropLast(value.scale)+"."+digits.takeLast(value.scale)
    }
}
internal fun boundedCardText(text: String, points: Int, bytes: Int) {
    checkFormat(text.length <= points*2 && validUtf16(text),Failure.ValidationError)
    checkFormat(text.codePointCount(0,text.length) <= points && utf8(text).size <= bytes,Failure.ValidationError)
}
internal fun notBlank(text: String) = text.codePoints().anyMatch { !UCharacter.isUWhiteSpace(it) }
