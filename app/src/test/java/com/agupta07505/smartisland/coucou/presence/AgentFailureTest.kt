package com.agupta07505.smartisland.coucou.presence

import com.agupta07505.smartisland.coucou.agents.AgentModel
import com.agupta07505.smartisland.coucou.agents.RemoteMsg
import org.junit.Assert.assertEquals
import org.junit.Test

/** A failed tool is what Mochi winces at; other events leave it alone. */
class AgentFailureTest {
    private fun event(name: String) = RemoteMsg.Event(name, "s1", "coucou", "Bash", "ls", "")

    @Test
    fun only_a_failed_tool_stamps_the_failure() {
        val m = AgentModel()
        m.apply(event("PreToolUse"), 100L)
        m.apply(event("PostToolUse"), 200L)
        assertEquals(0L, m.lastFailureAt)
        m.apply(event("PostToolUseFailure"), 300L)
        assertEquals(300L, m.lastFailureAt)
        m.apply(event("Stop"), 400L)
        assertEquals(300L, m.lastFailureAt)
    }
}
