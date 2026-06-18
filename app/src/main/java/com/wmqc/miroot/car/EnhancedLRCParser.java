
package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增强的LRC歌词解析器
 * 
 * 支持功能:
 * - 标准LRC格式 [mm:ss.xx]歌词文本
 * - 逐字时间戳格式 [mm:ss.xx]<开始时间,结束时间>字<开始时间,结束时间>字
 * - 双语歌词（翻译行识别）
 * - 元数据标签（ti, ar, al等）
 * - 多时间戳支持（一行歌词对应多个时间点）
 */
public class EnhancedLRCParser {
    private static final String TAG = "EnhancedLRCParser";
    
    /**
     * 逐字时间戳
     */
    public static class WordTimestamp {
        public String word;       // 字或词
        public long startTime;    // 开始时间(毫秒)
        public long endTime;      // 结束时间(毫秒)
        
        public WordTimestamp(String word, long startTime, long endTime) {
            this.word = word;
            this.startTime = startTime;
            this.endTime = endTime;
        }
        
        @Override
        public String toString() {
            return String.format("WordTimestamp{word='%s', start=%d, end=%d}", 
                               word, startTime, endTime);
        }
    }
    
    /**
     * 增强的歌词行
     */
    public static class EnhancedLyricLine {
        public long time;                              // 行开始时间(毫秒)
        public String text;                            // 歌词文本
        public String translation;                     // 翻译(如果有)
        public List<WordTimestamp> wordTimestamps;     // 逐字时间戳(如果有)
        public boolean isTranslationLine;              // 是否为翻译行
        
        public EnhancedLyricLine(long time, String text) {
            this.time = time;
            this.text = text;
            this.translation = null;
            this.wordTimestamps = new ArrayList<>();
            this.isTranslationLine = false;
        }
        
        @Override
        public String toString() {
            return String.format("EnhancedLyricLine{time=%d, text='%s', translation='%s', hasWordTimestamps=%b}", 
                               time, text, translation, wordTimestamps != null && !wordTimestamps.isEmpty());
        }
    }
    
    /**
     * 歌词元数据
     */
    public static class LyricMetadata {
        public String title = "";      // 歌名
        public String artist = "";     // 艺术家
        public String album = "";      // 专辑
        public String by = "";         // 歌词制作者
        public int offset = 0;         // 时间偏移(毫秒)
        
        @Override
        public String toString() {
            return String.format("LyricMetadata{title='%s', artist='%s', album='%s', offset=%d}", 
                               title, artist, album, offset);
        }
    }
    
    /**
     * 解析结果
     */
    public static class ParseResult {
        public List<EnhancedLyricLine> lines = new ArrayList<>();
        public LyricMetadata metadata = new LyricMetadata();
        
        public ParseResult() {}
    }
    
    /**
     * 解析增强的LRC歌词
     * 
     * @param lrcContent LRC内容
     * @return 解析结果
     */
    public static ParseResult parse(String lrcContent) {
        ParseResult result = new ParseResult();
        
        if (lrcContent == null || lrcContent.isEmpty()) {
            return result;
        }
        
        try {
            String[] lines = lrcContent.split("\n");
            
            // 标准时间戳格式: [mm:ss.xx]
            Pattern timePattern = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{2,3}))?\\]");
            
            // 逐字时间戳格式: <start,end>word 或 <start>word
            Pattern wordTimePattern = Pattern.compile("<(\\d+)(?:,(\\d+))?>([^<>]+)");
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                
                // 解析元数据标签
                if (parseMetadata(line, result.metadata)) {
                    continue;
                }
                
                // 查找所有时间戳
                List<Long> timestamps = new ArrayList<>();
                Matcher timeMatcher = timePattern.matcher(line);
                
                while (timeMatcher.find()) {
                    int minutes = Integer.parseInt(timeMatcher.group(1));
                    int seconds = Integer.parseInt(timeMatcher.group(2));
                    String centiStr = timeMatcher.group(3);
                    int centiseconds = 0;
                    
                    if (centiStr != null && !centiStr.isEmpty()) {
                        // 处理2位或3位的毫秒数
                        if (centiStr.length() == 2) {
                            centiseconds = Integer.parseInt(centiStr) * 10;
                        } else if (centiStr.length() == 3) {
                            centiseconds = Integer.parseInt(centiStr);
                        }
                    }
                    
                    long timeMs = (minutes * 60 + seconds) * 1000L + centiseconds;
                    timestamps.add(timeMs);
                }
                
                if (timestamps.isEmpty()) {
                    continue;
                }
                
                // 提取歌词文本（移除所有时间戳）
                String text = line.replaceAll("\\[\\d{1,2}:\\d{2}(?:\\.\\d{2,3})?\\]", "").trim();
                
                if (text.isEmpty()) {
                    continue;
                }
                
                // 检查是否包含逐字时间戳
                boolean hasWordTimestamps = text.contains("<") && text.contains(">");
                
                // 为每个时间戳创建一个歌词行
                for (Long timestamp : timestamps) {
                    EnhancedLyricLine lyricLine = new EnhancedLyricLine(timestamp, text);
                    
                    // 解析逐字时间戳（如果有）
                    if (hasWordTimestamps) {
                        parseWordTimestamps(text, timestamp, lyricLine);
                    }
                    
                    result.lines.add(lyricLine);
                }
            }
            
            // 按时间排序
            Collections.sort(result.lines, (a, b) -> Long.compare(a.time, b.time));
            
            // 应用时间偏移
            if (result.metadata.offset != 0) {
                for (EnhancedLyricLine line : result.lines) {
                    line.time += result.metadata.offset;
                    if (!line.wordTimestamps.isEmpty()) {
                        for (WordTimestamp word : line.wordTimestamps) {
                            word.startTime += result.metadata.offset;
                            word.endTime += result.metadata.offset;
                        }
                    }
                }
            }
            
            // 识别并关联翻译
            associateTranslations(result.lines);
            
            LogHelper.d(TAG, "解析完成: " + result.lines.size() + " 行歌词, 元数据: " + result.metadata);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "解析LRC失败", e);
        }
        
        return result;
    }
    
    /**
     * 解析元数据标签
     */
    private static boolean parseMetadata(String line, LyricMetadata metadata) {
        if (!line.startsWith("[") || !line.contains(":")) {
            return false;
        }
        
        try {
            int colonIndex = line.indexOf(":");
            int closeBracket = line.indexOf("]");
            
            if (colonIndex < 0 || closeBracket < 0 || closeBracket <= colonIndex) {
                return false;
            }
            
            String tag = line.substring(1, colonIndex).toLowerCase();
            String value = line.substring(colonIndex + 1, closeBracket).trim();
            
            switch (tag) {
                case "ti":
                    metadata.title = value;
                    return true;
                case "ar":
                    metadata.artist = value;
                    return true;
                case "al":
                    metadata.album = value;
                    return true;
                case "by":
                    metadata.by = value;
                    return true;
                case "offset":
                    try {
                        metadata.offset = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        LogHelper.w(TAG, "无效的offset值: " + value);
                    }
                    return true;
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "解析元数据失败: " + line, e);
        }
        
        return false;
    }
    
    /**
     * 解析逐字时间戳
     * 
     * 支持格式:
     * - <start,end>word - 指定开始和结束时间（相对行开始时间的毫秒数）
     * - <start>word - 只指定开始时间
     */
    private static void parseWordTimestamps(String text, long lineStartTime, 
                                           EnhancedLyricLine lyricLine) {
        try {
            Pattern wordTimePattern = Pattern.compile("<(\\d+)(?:,(\\d+))?>([^<>]+)");
            Matcher matcher = wordTimePattern.matcher(text);
            
            // 清理后的文本（移除时间戳标记）
            StringBuilder cleanText = new StringBuilder();
            
            long lastEndTime = 0;
            
            while (matcher.find()) {
                long startTime = Long.parseLong(matcher.group(1));
                String endTimeStr = matcher.group(2);
                long endTime = endTimeStr != null ? Long.parseLong(endTimeStr) : 0;
                String word = matcher.group(3);
                
                // 转换为绝对时间
                long absoluteStartTime = lineStartTime + startTime;
                long absoluteEndTime = endTime > 0 ? lineStartTime + endTime : 0;
                
                // 如果没有指定结束时间，使用下一个词的开始时间
                if (absoluteEndTime == 0) {
                    absoluteEndTime = absoluteStartTime + 500; // 默认500ms
                }
                
                WordTimestamp wordTimestamp = new WordTimestamp(word, absoluteStartTime, absoluteEndTime);
                lyricLine.wordTimestamps.add(wordTimestamp);
                
                cleanText.append(word);
                lastEndTime = absoluteEndTime;
            }
            
            // 更新清理后的文本
            if (cleanText.length() > 0) {
                lyricLine.text = cleanText.toString().trim();
            }
            
        } catch (Exception e) {
            LogHelper.w(TAG, "解析逐字时间戳失败: " + text, e);
        }
    }
    
    /**
     * 识别并关联翻译行
     * 
     * 翻译识别规则:
     * 1. 如果相邻两行时间戳相同或非常接近（<100ms），第二行视为第一行的翻译
     * 2. 如果一行的时间戳与上一行相同，且文本明显不同（如包含中文/英文），视为翻译
     */
    private static void associateTranslations(List<EnhancedLyricLine> lines) {
        if (lines.size() < 2) {
            return;
        }
        
        for (int i = 1; i < lines.size(); i++) {
            EnhancedLyricLine current = lines.get(i);
            EnhancedLyricLine previous = lines.get(i - 1);
            
            // 检查时间戳是否相同或非常接近
            long timeDiff = Math.abs(current.time - previous.time);
            
            if (timeDiff < 100 && !current.text.equals(previous.text)) {
                // 判断哪个是原文，哪个是翻译
                // 简单规则：如果当前行看起来像中文，之前的行看起来像英文，则当前行是翻译
                boolean currentIsChinese = containsChinese(current.text);
                boolean previousIsChinese = containsChinese(previous.text);
                
                if (currentIsChinese && !previousIsChinese) {
                    // 当前行是翻译
                    previous.translation = current.text;
                    current.isTranslationLine = true;
                } else if (!currentIsChinese && previousIsChinese) {
                    // 之前的行是翻译（罕见）
                    current.translation = previous.text;
                    previous.isTranslationLine = true;
                } else {
                    // 无法确定，默认第二行是翻译
                    previous.translation = current.text;
                    current.isTranslationLine = true;
                }
            }
        }
        
        // 移除标记为翻译的行（它们已经关联到原行）
        lines.removeIf(line -> line.isTranslationLine);
    }
    
    /**
     * 检查文本是否包含中文
     */
    private static boolean containsChinese(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 从普通文本创建歌词（兼容旧方法）
     */
    public static ParseResult parseAsPlainText(String text) {
        ParseResult result = new ParseResult();
        
        if (text == null || text.isEmpty()) {
            return result;
        }
        
        try {
            String[] lines = text.split("\n");
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    // 每行间隔3秒
                    long time = i * 3000L;
                    result.lines.add(new EnhancedLyricLine(time, line));
                }
            }
            
            LogHelper.d(TAG, "解析纯文本完成: " + result.lines.size() + " 行");
            
        } catch (Exception e) {
            LogHelper.e(TAG, "解析纯文本失败", e);
        }
        
        return result;
    }
}

