package io.github.munzzyy.tern.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

internal fun assertHolds(what: String, verdict: SignatureVerdict, scheme: Int, certificates: Set<String>, lineage: List<String> = emptyList()) {
    if (verdict !is SignatureVerdict.Holds) fail("$what: expected the signature to hold, got $verdict")
    verdict as SignatureVerdict.Holds
    assertEquals("$what: scheme", scheme, verdict.scheme)
    assertEquals("$what: certificates", certificates, verdict.certificates.toSet())
    assertEquals("$what: certificates named twice", verdict.certificates.size, verdict.certificates.toSet().size)
    assertEquals("$what: lineage", lineage, verdict.lineage)
}

/** Refused, and for the reason the test is about: [because] has to be part of what goes to the log. */
internal fun assertRefused(what: String, verdict: SignatureVerdict, because: String) {
    if (verdict !is SignatureVerdict.DoesNotHold) fail("$what: expected a refusal, got $verdict")
    verdict as SignatureVerdict.DoesNotHold
    assertTrue("$what: refused for another reason: ${verdict.reason}", verdict.reason.contains(because))
}

internal fun assertNotChecked(what: String, verdict: SignatureVerdict, because: String) {
    if (verdict !is SignatureVerdict.CannotVerify) fail("$what: expected no answer, got $verdict")
    verdict as SignatureVerdict.CannotVerify
    assertTrue("$what: left alone for another reason: ${verdict.reason}", verdict.reason.contains(because))
}
