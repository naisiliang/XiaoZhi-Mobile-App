package com.lchuang.xiaozhimobile

enum class ExitDecision { EXIT, CONTINUE, AMBIGUOUS }

class ConversationExitDetector {
    private val targetedExit = listOf(
        "退出微信", "退出qq", "退出登录", "退出当前账号", "退出账号",
        "退出这个页面", "关闭微信", "关闭qq", "关闭高德", "关闭百度地图"
    )

    private val strongExit = listOf(
        "退出", "退出吧", "退下", "退下吧", "你退下", "你退下吧",
        "没什么事了", "没事了", "没事你先退下", "不用了", "先这样吧", "就这样吧",
        "结束吧", "结束对话", "你先休息吧", "可以休息了", "休息吧",
        "再见", "拜拜", "今天先到这里", "暂时没别的事", "暂时没别的事情"
    )

    fun classify(raw: String): ExitDecision {
        val text = normalize(raw)
        if (text.isBlank()) return ExitDecision.CONTINUE

        if (targetedExit.any(text::contains)) return ExitDecision.CONTINUE
        if (Regex("(?:退出|关闭).*(?:应用|软件|页面|界面|账号|账户|密码|登录|微信|qq|地图)").containsMatchIn(text)) {
            return ExitDecision.CONTINUE
        }
        if (text.startsWith("怎么退出")) return ExitDecision.CONTINUE

        if (strongExit.any { text == it || text.contains(it) }) return ExitDecision.EXIT

        if (
            Regex("今天.*(?:就这样|先到这|到这里)").containsMatchIn(text) ||
            text.contains("先忙你的") ||
            text.contains("先忙你") ||
            text.contains("没别的事情") ||
            text.contains("没有别的事情")
        ) return ExitDecision.AMBIGUOUS

        return ExitDecision.CONTINUE
    }

    private fun normalize(raw: String): String = raw
        .trim()
        .lowercase()
        .replace(Regex("""[\s，。！？、,.!?；;：:"'（）()【】\[\]]+"""), "")
}
