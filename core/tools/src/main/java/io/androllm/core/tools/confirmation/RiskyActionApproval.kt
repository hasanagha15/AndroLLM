package io.androllm.core.tools.confirmation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Branch M: coroutine-context marker set by the ToolExecutor around
 * `execute()` of ONE call after the owner tapped Approve on its risky-action
 * card ([io.androllm.core.tools.api.Tool.riskyAction]). The UI tools refuse a
 * risky tap / submit when it is missing, so no path that bypasses the
 * executor (direct calls, replays) can tap Send / Pay / Delete unconfirmed.
 */
class RiskyActionApproval(val callId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RiskyActionApproval>
}

/** True when the current call was approved on a tap-only risky-action card. */
suspend fun isRiskyActionApproved(): Boolean = currentCoroutineContext()[RiskyActionApproval] != null
