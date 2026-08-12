package com.taka0.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretInputTest {

    @Test
    fun `空欄・空白のみの入力は既存値を保持する`() {
        assertEquals("sk-old", resolveSecretInput("", "sk-old"))
        assertEquals("sk-old", resolveSecretInput("   ", "sk-old"))
    }

    @Test
    fun `入力があればtrimして置き換える`() {
        assertEquals("sk-new", resolveSecretInput(" sk-new ", "sk-old"))
    }

    @Test
    fun `既存値が空でも入力どおり保存できる`() {
        assertEquals("sk-first", resolveSecretInput("sk-first", ""))
    }
}
