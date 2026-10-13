package com.agupta07505.smartisland.coucou

import com.agupta07505.smartisland.coucou.agents.ActivityKind
import com.agupta07505.smartisland.coucou.agents.AgentActivity
import com.agupta07505.smartisland.coucou.agents.AgentModel
import com.agupta07505.smartisland.coucou.agents.RemoteMsg
import com.agupta07505.smartisland.coucou.agents.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityTest {
    private fun working(tool: String, detail: String = "") = AgentActivity.of(tool, detail, SessionState.Working)

    @Test
    fun no_tool_yet_means_thinking() {
        val a = working("")
        assertEquals(ActivityKind.Thinking, a.kind)
        assertEquals("Thinking", a.line)
    }

    @Test
    fun files_are_named_by_their_last_segment() {
        assertEquals("Editing Mochi.kt", working("Edit", "/home/alan/dev/app/src/Mochi.kt").line)
        assertEquals("Reading Cargo.toml", working("Read", "/home/alan/dev/coucou/Cargo.toml").line)
        assertEquals("Writing notes.md", working("Write", "/tmp/notes.md").line)
    }

    @Test
    fun a_command_is_shown_cut_to_one_line() {
        val a = working("Bash", "cargo test --lib   \n  --no-fail-fast")
        assertEquals(ActivityKind.Running, a.kind)
        assertEquals("Running cargo test --lib", a.line)
        val long = working("Bash", "x".repeat(200))
        assertTrue(long.subject.length <= AgentActivity.SUBJECT_MAX)
        assertTrue(long.subject.endsWith("…"))
    }

    @Test
    fun searches_and_the_web_have_their_own_kinds() {
        assertEquals(ActivityKind.Searching, working("Grep", "fn main").kind)
        assertEquals(ActivityKind.Searching, working("Glob", "**/*.kt").kind)
        assertEquals("Fetching docs.rs", working("WebFetch", "https://www.docs.rs/serde/latest").line)
        assertEquals(ActivityKind.Browsing, working("WebSearch", "kotlin stateflow").kind)
    }

    @Test
    fun a_subagent_and_a_plan_are_named() {
        assertEquals("Delegating Review the diff", working("Task", "Review the diff").line)
        assertEquals(ActivityKind.Planning, working("TodoWrite").kind)
    }

    @Test
    fun mcp_tools_read_as_server_and_action() {
        assertEquals("Using github · create issue", working("mcp__github__create_issue").line)
    }

    @Test
    fun waiting_for_the_person_and_idle_ignore_the_tool() {
        val ask = AgentActivity.of("Bash", "rm -rf build", SessionState.NeedsYou)
        assertEquals(ActivityKind.Asking, ask.kind)
        assertEquals("Idle", AgentActivity.of("Bash", "ls", SessionState.Idle).line)
        // A bare "needs you" has nothing to add.
        assertEquals("Needs you", AgentActivity.of("", "needs you", SessionState.NeedsYou).line)
    }

    @Test
    fun elapsed_is_said_for_a_glance() {
        assertEquals("0 s", AgentActivity.elapsed(-5))
        assertEquals("45 s", AgentActivity.elapsed(45_000))
        assertEquals("2 min 14 s", AgentActivity.elapsed(134_000))
        assertEquals("1 h 05 min", AgentActivity.elapsed(3_900_000))
    }

    @Test
    fun a_run_counts_its_steps_and_keeps_its_start() {
        val m = AgentModel()
        fun ev(name: String, tool: String = "", at: Long) =
            m.apply(RemoteMsg.Event(name, "s1", "coucou", tool, "d", ""), at)
        ev("UserPromptSubmit", at = 1_000)
        ev("PreToolUse", "Read", at = 2_000)
        ev("PostToolUse", "Read", at = 2_500)
        ev("PreToolUse", "Edit", at = 3_000)
        val s = m.view(3_100).single()
        assertEquals(1_000L, s.since)
        assertEquals(2, s.steps)
        ev("Stop", at = 4_000)
        val done = m.view(4_100).single()
        assertEquals(SessionState.Idle, done.state)
        // A new prompt starts a new run.
        ev("UserPromptSubmit", at = 9_000)
        val again = m.view(9_100).single()
        assertEquals(9_000L, again.since)
        assertEquals(0, again.steps)
    }
}
