package com.example.systemhealth

import org.junit.Assert.*
import org.junit.Test

class RequestNoticePolicyTest {
    @Test fun reorderedRequestsDoNotNotifyAgain() {
        assertFalse(RequestNoticePolicy.shouldUpdate(listOf("one", "two"), listOf("two", "one")))
    }
    @Test fun newOrCompletedRequestUpdatesTheNotice() {
        assertTrue(RequestNoticePolicy.shouldUpdate(listOf("one"), listOf("one", "two")))
        assertTrue(RequestNoticePolicy.shouldUpdate(listOf("one", "two"), listOf("two")))
    }
    @Test fun retriesDoNotCreateAnotherAlert() {
        assertFalse(RequestNoticePolicy.shouldUpdate(listOf("one"), listOf("one", "one")))
    }
}
