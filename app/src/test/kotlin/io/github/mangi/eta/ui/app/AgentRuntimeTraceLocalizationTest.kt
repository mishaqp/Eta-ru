package io.github.mangi.eta.ui.app

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimeTraceLocalizationTest {
    private val russian = Locale.forLanguageTag("ru-RU")

    @Test
    fun localizesToolTraceSeenInRussianChat() {
        assertEquals(
            "Терминал · Однократный запуск · Android · root",
            AgentRuntimeTraceLocalization.localize("终端 · 单次执行 · Android · root", russian),
        )
        assertEquals(
            "Просмотр экрана · со скриншотом · с деревом интерфейса",
            AgentRuntimeTraceLocalization.localize("观察屏幕 · 含截图 · 含界面树", russian),
        )
        assertEquals(
            "Вставка текста · 0 симв.",
            AgentRuntimeTraceLocalization.localize("粘贴文本 · 0 字符", russian),
        )
        assertEquals(
            "Ожидание · 30 с",
            AgentRuntimeTraceLocalization.localize("等待 · 30 秒", russian),
        )
        assertEquals(
            "Кнопка · Назад",
            AgentRuntimeTraceLocalization.localize("按键 · 返回", russian),
        )
    }

    @Test
    fun keepsRealTerminalOutputUntouched() {
        assertEquals(
            "Команда выполнена\n终端 output must stay byte-for-byte",
            AgentRuntimeTraceLocalization.localize(
                "执行完成\n终端 output must stay byte-for-byte",
                russian,
            ),
        )
    }

    @Test
    fun leavesNonRussianLocaleUntouched() {
        val source = "观察屏幕 · 含截图 · 含界面树"
        assertEquals(
            source,
            AgentRuntimeTraceLocalization.localize(source, Locale.ENGLISH),
        )
    }
}
