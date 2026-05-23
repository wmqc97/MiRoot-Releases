package com.wmqc.miroot.lyrics

object SimpleChineseTokenizer {
    private val dictionary = listOf(
        // 常见连接与语气
        "就是", "然后", "因为", "所以", "如果", "可以", "只要", "直到", "终于", "后来", "曾经", "原来",
        "已经", "只是", "还是", "不是", "真的", "现在", "这样", "这种", "一个",

        // 时间与场景
        "今天", "明天", "昨天", "夜晚", "白天", "时间", "回忆", "未来", "过去", "路上", "耳边", "脑海",
        "风里", "雨里", "梦里", "心中", "眼中", "手里", "身边", "远方",

        // 人称与关系
        "你我", "你们", "我们", "咱们", "他们", "她们", "它们", "大家", "自己", "本人",
        "你和我", "我和你", "你和他", "我和他", "你和她", "我和她", "你和它",
        "与你", "与我", "与他", "与她", "与它", "与我们", "与他们", "与大家",
        "对你", "对我", "对他", "对她", "对它", "对我们", "对他们", "对大家",
        "像你", "像我", "像他", "像她", "像它", "属于", "关于", "关于你", "关于我",
        "我们俩", "咱们俩", "他们俩", "她们俩", "它们俩", "大家一起", "所有人",

        // 情绪与动作
        "喜欢", "快乐", "孤单", "温柔", "热烈", "安静", "喧闹", "微笑", "拥抱", "离开", "回来", "再见",
        "相信", "记得", "忘记", "等待", "靠近", "停留", "沉默", "呼吸", "余温", "心跳", "眼泪",
        "开始", "结束", "慢慢", "轻轻", "悄悄", "再次",

        // 歌词常见固定搭配
        "你知道", "不知道", "我知道", "看着你", "想着你", "拥抱着", "别走",
        "心里", "眼睛", "光芒", "星光", "月光", "阳光", "世界",

        // 音乐与背屏相关词（用于车载场景歌词分词）
        "音乐", "歌词", "副歌", "主歌", "前奏", "间奏", "尾奏", "旋律", "节奏", "鼓点", "和声",
        "高音", "低音", "人声", "纯音乐", "原唱", "翻唱", "单曲", "专辑", "歌单", "曲目",
        "车载", "车机", "背屏", "后排", "投屏", "媒体会话", "媒体控制", "播放状态", "当前歌曲",
        "歌词同步", "逐字歌词", "逐行歌词", "锁屏歌词", "桌面歌词", "实时歌词", "歌词行", "歌词词",
        "酷我", "酷我音乐", "网易云", "QQ音乐", "汽水音乐",

        // 新增：歌词高频短语与口语搭配
        "可不可以", "要不要", "算不算", "会不会", "能不能", "来不及", "舍不得", "放不下", "忘不了", "看不见",
        "听不见", "回不去", "回不到", "等不到", "留不住", "抓不住", "停不下", "慢一点", "快一点", "再一次",
        "这一刻", "那一刻", "这一秒", "下一秒", "这一生", "这一世", "这座城", "那座城", "这条路", "那条路",
        "我以为", "你以为", "你是否", "我是否", "你愿意", "我愿意", "我想要", "你想要", "不需要", "没关系",
        "对不起", "谢谢你", "谢谢你们", "告诉我", "告诉你", "陪着你", "陪着我", "守护你", "守护我", "抱着你",
        "抱着我", "看见你", "看见我", "遇见你", "遇见你们", "想起你", "想起我", "忘了你", "忘了我", "离不开",
        "走下去", "留下来", "追不上", "跟不上", "不后悔", "别回头", "别害怕", "别难过", "别哭了", "别放手",
        "别离开", "别再说", "不再见", "再见面", "好不好", "行不行", "是不是", "对不对", "要多久", "有一天",
        "每一天", "每一夜", "每一次", "某一天", "某一夜", "某一次", "一瞬间", "一辈子", "一段路", "一路上",

        // 新增：情绪、关系、故事向词组
        "心里面", "心上人", "心头上", "眼神里", "笑容里", "回忆里", "故事里", "梦境里", "城市里", "夜色里",
        "风声里", "雨声里", "人海里", "人群里", "角落里", "站台上", "车窗外", "路灯下", "晚风里", "清晨里",
        "黄昏里", "黎明前", "天亮了", "天黑了", "雨停了", "风停了", "春天里", "夏天里", "秋天里", "冬天里",
        "爱情里", "生活里", "世界里", "宇宙里", "永远都", "从此后", "到最后", "后来呢", "原来你", "原来我",
        "曾经你", "曾经我", "终于你", "终于我", "还是你", "还是我", "只有你", "只有我", "除了你", "除了我",
        "为了你", "为了我", "给你听", "给我听", "跟着你", "跟着我", "爱着你", "爱着我", "想着你", "念着你",
        "等着你", "等着我", "望着你", "望着我", "留给你", "留给我", "属于你", "属于我", "关于你", "关于他",

        // 新增：车载听歌与播放行为词
        "正在播放", "下一首", "上一首", "单曲循环", "顺序播放", "随机播放", "继续播放", "暂停播放", "开始播放", "停止播放",
        "歌词匹配", "歌词加载", "歌词更新", "歌词解析", "歌词来源", "歌词状态", "媒体按钮", "播放进度", "当前进度", "歌曲信息",
        "歌手信息", "专辑信息", "本地音乐", "在线音乐", "车机蓝牙", "蓝牙音乐", "导航语音", "语音助手", "后排显示", "副屏显示",

        // 兼容历史词条
        "你知道", "不知道", "我知道", "我们", "现在", "真的", "就是", "然后", "因为", "所以",
        "如果", "可以", "喜欢", "快乐", "世界", "音乐", "歌词", "心里", "眼睛", "时间",
        "回忆", "未来", "过去", "一直", "一个", "这种", "这样", "那里", "这里", "还是",
        "已经", "只是", "不是", "开始", "结束", "今天", "明天", "昨天", "夜晚", "白天",
        "风里", "雨里", "梦里", "心中", "眼中", "手里", "身边", "路上", "耳边", "脑海",
        "孤单", "温柔", "热烈", "安静", "喧闹", "微笑", "拥抱", "离开", "回来", "再见",
        "相信", "记得", "忘记", "等待", "靠近", "远方", "光芒", "星光", "月光", "阳光",
        "只要", "直到", "终于", "慢慢", "轻轻", "悄悄", "再次", "后来", "曾经", "原来",
        "别走", "停留", "沉默", "呼吸", "余温", "心跳", "眼泪", "拥抱着", "看着你", "想着你",
        "你我", "我们", "他们", "她们", "它们", "你们", "咱们", "自己", "本人", "大家",
        "你和我", "我和你", "你和他", "我和他", "你和她", "我和她", "你和它",
        "与我", "与你", "与他", "与她", "与它", "与我们", "与他们", "与大家",
        "你们", "我们俩", "咱们俩", "他们俩", "她们俩", "它们俩", "大家一起", "所有人",
        "对你", "对我", "对他", "对她", "对它", "对我们", "对他们", "对大家",
        "像你", "像我", "像他", "像她", "像它", "属于", "关于", "关于你", "关于我"
    ).distinct().sortedByDescending { it.length }

    private val trieRoot = buildTrie(dictionary)
    private val maxWordLength = dictionary.maxOfOrNull { it.length } ?: 0

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isWhitespace() -> i++
                isPunctuation(ch) -> {
                    i++
                }
                !ch.isCjk() -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() && !isPunctuation(text[i]) && !text[i].isCjk()) i++
                    out += text.substring(start, i)
                }
                else -> {
                    val matched = longestMatch(text, i)
                    if (matched != null) {
                        out += matched
                        i += matched.length
                    } else {
                        val start = i
                        while (i < text.length && text[i].isCjk() && !isPunctuation(text[i]) && !text[i].isWhitespace()) i++
                        out += fallbackSplit(text.substring(start, i))
                    }
                }
            }
        }
        return out.filter { it.isNotBlank() && !it.all { ch -> isPunctuation(ch) } }
    }

    private fun longestMatch(text: String, start: Int): String? {
        var node = trieRoot
        var best: String? = null
        var i = start
        while (i < text.length) {
            val c = text[i]
            node = node.children[c] ?: break
            if (node.word != null) best = node.word
            i++
            if (i - start > maxWordLength) break
        }
        return best
    }

    private fun fallbackSplit(chunk: String): List<String> {
        if (chunk.isBlank()) return emptyList()
        if (chunk.length <= 2) return listOf(chunk)
        val result = mutableListOf<String>()
        var i = 0
        while (i < chunk.length) {
            val remain = chunk.length - i
            val take = when {
                remain >= 4 -> 2
                remain == 3 -> 2
                else -> 1
            }
            result += chunk.substring(i, i + take)
            i += take
        }
        return result
    }

    private fun buildTrie(words: List<String>): TrieNode {
        val root = TrieNode()
        for (word in words) {
            var node = root
            for (c in word) {
                node = node.children.getOrPut(c) { TrieNode() }
            }
            node.word = word
        }
        return root
    }

    private class TrieNode {
        val children: MutableMap<Char, TrieNode> = linkedMapOf()
        var word: String? = null
    }

    private fun Char.isCjk(): Boolean {
        val block = Character.UnicodeBlock.of(this)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA
    }

    private fun isPunctuation(ch: Char): Boolean = ch.toString().matches(Regex("\\p{Punct}"))
}
