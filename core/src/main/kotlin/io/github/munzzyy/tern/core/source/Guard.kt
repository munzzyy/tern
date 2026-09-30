package io.github.munzzyy.tern.core.source

import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.net.RateLimitedException
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.net.ValidatorStore
import io.github.munzzyy.tern.core.xml.XmlException
import java.io.IOException

/** Holds validator writes until a check has succeeded, so a failed check is asked again in full. */
internal class PendingValidators(private val target: ValidatorStore) : ValidatorStore {
    private val pending = LinkedHashMap<String, Validator?>()

    override fun get(key: String): Validator? = if (pending.containsKey(key)) pending[key] else target.get(key)

    override fun put(key: String, validator: Validator) {
        pending[key] = validator
    }

    override fun remove(key: String) {
        pending[key] = null
    }

    fun commit() {
        for ((key, validator) in pending) {
            if (validator == null) target.remove(key) else target.put(key, validator)
        }
    }
}

/**
 * Runs one check so that it either succeeds as a whole or leaves no trace, and so that every
 * failure reaches the caller as a [SourceException].
 */
internal inline fun <T> guarded(context: CheckContext, check: (CheckContext) -> T): T {
    val validators = PendingValidators(context.validators)
    val scoped = context.withValidators(validators)
    val result = try {
        check(scoped)
    } catch (e: SourceException) {
        throw e
    } catch (e: RateLimitedException) {
        throw SourceException(SourceErrorKind.RATE_LIMITED, "Rate limited by ${e.host}", e.retryAtMs, e)
    } catch (e: JsonException) {
        throw SourceException(SourceErrorKind.PARSE, "The answer was not valid JSON", cause = e)
    } catch (e: XmlException) {
        throw SourceException(SourceErrorKind.PARSE, "The feed could not be read", cause = e)
    } catch (e: IOException) {
        throw SourceException(SourceErrorKind.NETWORK, e.message ?: "The connection failed", cause = e)
    } catch (e: RuntimeException) {
        throw SourceException(SourceErrorKind.PARSE, "The answer could not be read", cause = e)
    }
    validators.commit()
    return result
}
