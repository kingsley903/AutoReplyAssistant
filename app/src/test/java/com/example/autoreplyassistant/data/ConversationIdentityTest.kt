package com.example.autoreplyassistant.data

import org.junit.Test
import org.junit.Assert.*

class ConversationIdentityTest {

    @Test
    fun `individual conversation identity`() {
        val identity = ConversationIdentity(
            packageName = "com.whatsapp",
            conversationKey = "com.whatsapp|Contact A",
            displayName = "Contact A",
            isGroup = false
        )
        assertEquals("com.whatsapp", identity.packageName)
        assertEquals("com.whatsapp|Contact A", identity.conversationKey)
        assertEquals("Contact A", identity.displayName)
        assertFalse(identity.isGroup)
        assertEquals("com.whatsapp/com.whatsapp|Contact A (individual: Contact A)", identity.toLogString())
    }

    @Test
    fun `group conversation identity`() {
        val identity = ConversationIdentity(
            packageName = "com.whatsapp.w4b",
            conversationKey = "com.whatsapp.w4b|Group X",
            displayName = "Group X",
            isGroup = true
        )
        assertEquals("com.whatsapp.w4b", identity.packageName)
        assertTrue(identity.isGroup)
        assertEquals("com.whatsapp.w4b/com.whatsapp.w4b|Group X (group: Group X)", identity.toLogString())
    }

    @Test
    fun `different conversations have different keys`() {
        val individual = ConversationIdentity("com.whatsapp", "com.whatsapp|Alice", "Alice", false)
        val group = ConversationIdentity("com.whatsapp", "com.whatsapp|Family Group", "Family Group", true)
        val business = ConversationIdentity("com.whatsapp.w4b", "com.whatsapp.w4b|Alice", "Alice", false)

        assertNotEquals(individual.conversationKey, group.conversationKey)
        assertNotEquals(individual.conversationKey, business.conversationKey)
        assertNotEquals(group.conversationKey, business.conversationKey)
    }
}