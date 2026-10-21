package com.fantadrom.copperscroll.storage.cards

import com.fantadrom.copperscroll.storage.format.*
import com.ibm.icu.lang.UCharacter
import java.net.URI

object CardCodec {
    fun validate(title: String, body: CardBody): CardValidation {
        val errors = ArrayList<CardIssue>(); val warnings = ArrayList<CardIssue>()
        try { boundedCardText(title,100,400); checkFormat(notBlank(title) && '\r' !in title && '\n' !in title,Failure.ValidationError) }
        catch(_: FormatException) { errors.add(CardIssue(null,"Title")) }
        val ids = HashSet<Bytes>()
        for(field in body.fields) {
            try {
                field.id.id(16); checkFormat(ids.add(field.id),Failure.ValidationError)
                boundedCardText(field.name,128,512); validateField(field)
                val value = (field.value as? TextValue)?.text.orEmpty()
                if(value.isNotEmpty() && field.type == 4) {
                    val uri = try { URI(value) } catch(_: Exception) { null }
                    if(uri?.scheme !in listOf("http","https") || uri?.host.isNullOrEmpty()) warnings.add(CardIssue(field.id,"UrlSyntax"))
                }
                if(value.isNotEmpty() && field.type == 5 && !value.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                    warnings.add(CardIssue(field.id,"EmailSyntax"))
            } catch(e: FormatException) { errors.add(CardIssue(field.id,if(e.failure == Failure.UnsupportedVersion) "Unsupported" else "Field")) }
        }
        if(errors.isEmpty()) try { encode(body).fill(0) } catch(_: FormatException) { errors.add(CardIssue(null,"BodyLimit")) }
        return CardValidation(errors,warnings)
    }
    internal fun requireValid(body: CardBody) {
        val ids = HashSet<Bytes>(); var total = 0L
        for(f in body.fields) {
            f.id.id(16); checkFormat(ids.add(f.id),Failure.ValidationError); boundedCardText(f.name,128,512); validateField(f)
            total += utf8(f.name).size+256L
            if(f.value is TextValue) total += utf8(f.value.text).size
            checkFormat(total <= CardLimits.BODY,Failure.UnsupportedLimit)
        }
    }
    private fun validateField(f: CardField) {
        checkFormat(f.type in 1..10,Failure.UnsupportedVersion)
        when(f.type) {
            in 1..5 -> {
                checkFormat(f.value is TextValue,Failure.ValidationError); val text = (f.value as TextValue).text
                boundedCardText(text,CardLimits.TEXT_POINTS,CardLimits.TEXT_BYTES)
                if(f.type == 1 || f.type == 3) checkFormat('\n' !in text && '\r' !in text,Failure.ValidationError)
                if(f.type == 1 || f.type == 3) {
                    val p = f.parameters as? TextParameters ?: throw FormatException(Failure.ValidationError)
                    checkFormat(p.maxLength in 1..CardLimits.TEXT_POINTS && text.codePointCount(0,text.length) <= p.maxLength,Failure.ValidationError)
                    checkFormat(!(p.digitsOnly && p.uppercaseOnly) && !(p.mask != null && p.uppercaseOnly),Failure.ValidationError)
                    if(p.digitsOnly) checkFormat(text.all { it in '0'..'9' },Failure.ValidationError)
                    if(p.uppercaseOnly) checkFormat(text.codePoints().allMatch { UCharacter.isUpperCase(it) },Failure.ValidationError)
                    p.mask?.let { DigitMask.parse(it).validateRaw(text) }
                } else checkFormat(f.parameters === NoParameters,Failure.ValidationError)
            }
            6 -> {
                val p = f.parameters as? DateParameters ?: throw FormatException(Failure.ValidationError)
                DateMask.parse(p.mask); checkFormat(f.value is DateValue,Failure.ValidationError)
                (f.value as DateValue).date?.let { DateMask.validateDate(it) }
            }
            7 -> {
                val p = f.parameters as? PatternParameters ?: throw FormatException(Failure.ValidationError)
                val v = f.value as? PatternValue ?: throw FormatException(Failure.ValidationError)
                checkFormat(p.side in 2..6 && v.points.all { it in 1..p.side*p.side } && v.points.distinct().size == v.points.size,Failure.ValidationError)
            }
            8 -> { checkFormat(f.parameters === NoParameters && f.value is IntegerValue,Failure.ValidationError); ExactNumbers.validateInteger((f.value as IntegerValue).integer) }
            9 -> { checkFormat(f.parameters === NoParameters && f.value is DecimalValue,Failure.ValidationError); ExactNumbers.format(f.value as DecimalValue) }
            10 -> checkFormat(f.parameters === NoParameters && f.value is BooleanValue,Failure.ValidationError)
        }
    }
    fun encode(body: CardBody): ByteArray {
        requireValid(body)
        val fields = body.fields.map { f ->
            val p: Any? = when(val p = f.parameters) {
                NoParameters -> null; is TextParameters -> listOf(p.mask,p.digitsOnly,p.uppercaseOnly,p.maxLength)
                is DateParameters -> listOf(p.mask); is PatternParameters -> listOf(p.side)
            }
            val v: Any? = when(val v = f.value) {
                is TextValue -> v.text; is DateValue -> v.date?.let { listOf(it.year,it.month,it.day) }
                is PatternValue -> v.points; is IntegerValue -> v.integer; is DecimalValue -> listOf(v.unscaled,v.scale)
                is BooleanValue -> v.enabled
            }
            listOf(f.id,f.type,f.name,p,v)
        }
        return StrictCbor.encode(listOf(1,fields),CardLimits.BODY)
    }
    fun decode(bytes: ByteArray): CardBody {
        val a = StrictCbor.decode(bytes,CardLimits.BODY).arr(2); a[0].version()
        val fields = a[1].arr(); checkFormat(fields.size <= CardLimits.FIELDS,Failure.UnsupportedLimit)
        val result = fields.map { item ->
            val f = item.arr(5); val type = f[1].number(255).toInt(); checkFormat(type in 1..10,Failure.UnsupportedVersion)
            val p: FieldParameters = when(type) {
                1,3 -> f[3].arr(4).let { TextParameters(it[0]?.text(1024),it[1].bool(),it[2].bool(),it[3].number(32768).toInt()) }
                6 -> DateParameters(f[3].arr(1)[0].text(64)); 7 -> PatternParameters(f[3].arr(1)[0].number(6).toInt())
                else -> { checkFormat(f[3] == null); NoParameters }
            }
            val v: CardValue = when(type) {
                in 1..5 -> TextValue(f[4].text(CardLimits.TEXT_BYTES))
                6 -> DateValue(f[4]?.arr(3)?.let { CardDate(it[0].number(9999).toInt(),it[1].number(12).toInt(),it[2].number(31).toInt()) })
                7 -> PatternValue(f[4].arr().also { checkFormat(it.size <= 36) }.map { it.number(36).toInt() })
                8 -> IntegerValue(f[4].text(39)); 9 -> f[4].arr(2).let { DecimalValue(it[0].text(39),it[1].number(18).toInt()) }
                else -> BooleanValue(f[4].bool())
            }
            CardField(f[0].ident(),type,f[2].text(512),p,v)
        }
        return CardBody(result).also { requireValid(it) }
    }
}
