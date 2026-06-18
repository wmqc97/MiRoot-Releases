
package com.wmqc.miroot.lyrics;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 歌词匹配评分工具类
 * 根据文档规范实现匹配评分算法
 */
public class LyricsMatcher {
    private static final String TAG = "LyricsMatcher";
    
    /**
     * 查询信息
     */
    public static class Query {
        public String title = "";
        public String artist = "";
        public String album = "";
        public double duration = 0.0; // 秒
        
        public Query(String title, String artist) {
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
        }
        
        public Query(String title, String artist, String album, double duration) {
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.duration = duration;
        }
    }
    
    /**
     * 候选结果
     */
    public static class Candidate {
        public String id = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public double duration = 0.0; // 秒
        public double score = 0.0; // 匹配分数
        public String[] formats = {}; // 支持的格式
        public String provider = ""; // 提供商
        
        public Candidate(String id, String title, String artist, String album, double duration, 
                        String[] formats, String provider) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.duration = duration;
            this.formats = formats != null ? formats : new String[0];
            this.provider = provider != null ? provider : "";
        }
    }
    
    /**
     * 计算Levenshtein编辑距离
     */
    public static int levenshtein(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        
        int lenA = a.length();
        int lenB = b.length();
        
        if (lenA == 0) return lenB;
        if (lenB == 0) return lenA;
        
        int[][] m = new int[lenB + 1][lenA + 1];
        
        // 初始化第一行和第一列
        for (int i = 0; i <= lenB; i++) {
            m[i][0] = i;
        }
        for (int j = 0; j <= lenA; j++) {
            m[0][j] = j;
        }
        
        // 填充矩阵
        for (int i = 1; i <= lenB; i++) {
            for (int j = 1; j <= lenA; j++) {
                int cost = (b.charAt(i - 1) == a.charAt(j - 1)) ? 0 : 1;
                m[i][j] = Math.min(
                    Math.min(m[i - 1][j] + 1, m[i][j - 1] + 1),
                    m[i - 1][j - 1] + cost
                );
            }
        }
        
        return m[lenB][lenA];
    }
    
    /**
     * 归一化字符串：小写、NFKC、去空白与标点
     */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        
        // 转换为小写
        String normalized = s.toLowerCase();
        
        // NFKC规范化（Unicode规范化）
        try {
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC);
        } catch (Exception e) {
            LogHelper.w(TAG, "NFKC规范化失败: " + e.getMessage());
        }
        
        // 去除所有空白字符
        normalized = normalized.replaceAll("\\s+", "");
        
        // 去除标点符号，但保留中文字符、英文字母和数字
        normalized = normalized.replaceAll("[^\\w\\u4e00-\\u9fff]", "");
        
        return normalized;
    }
    
    /**
     * 计算相似度（基于Levenshtein距离）
     */
    public static double similarity(String x, String y) {
        String a = normalize(x);
        String b = normalize(y);
        
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        
        int distance = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        
        if (maxLen == 0) {
            return 1.0;
        }
        
        return 1.0 - (double) distance / maxLen;
    }
    
    /**
     * 生成缓存键
     * 格式：norm(title)+norm(artist)+round(duration)
     */
    public static String generateCacheKey(String title, String artist, double duration) {
        String normTitle = normalize(title);
        String normArtist = normalize(artist);
        long roundedDuration = Math.round(duration);
        return normTitle + normArtist + roundedDuration;
    }
    
    /**
     * V3.16: 使用小张桌面的匹配评分算法
     * 计算匹配分数（返回整数分数，用于排序）
     */
    public static int scoreInt(Query query, Candidate candidate) {
        String queryTitle = query.title != null ? query.title.toLowerCase() : "";
        String queryArtist = query.artist != null ? query.artist.toLowerCase() : "";
        String candidateTitle = candidate.title != null ? candidate.title.toLowerCase() : "";
        String candidateArtist = candidate.artist != null ? candidate.artist.toLowerCase() : "";
        
        String cleanQueryTitle = cleanTitle(queryTitle);
        String cleanCandidateTitle = cleanTitle(candidateTitle);
        
        int score = 0;
        
        // 标题完全匹配：+100分
        if (queryTitle.equals(candidateTitle)) {
            score += 100;
        }
        
        // 艺术家完全匹配：+50分
        if (queryArtist.equals(candidateArtist)) {
            score += 50;
        }
        
        // 标题+艺术家组合匹配：+200分
        String queryCombined = queryArtist + " " + queryTitle;
        String candidateCombined = candidateArtist + " " + candidateTitle;
        if (queryCombined.equals(candidateCombined)) {
            score += 200;
        }
        
        // 清理后的标题包含匹配：+50分或+30分
        if (cleanQueryTitle.contains(cleanCandidateTitle)) {
            score += 50;
        } else if (cleanCandidateTitle.contains(cleanQueryTitle)) {
            score += 30;
        }
        
        // 艺术家分割匹配（处理多个艺术家）
        if (queryArtist.length() > 0) {
            String[] artistParts = queryArtist.split("[,&、/]");
            for (String part : artistParts) {
                part = part.trim();
                if (part.isEmpty()) continue;
                
                if (candidateArtist.equals(part)) {
                    score += 40;
                } else if (candidateArtist.contains(part) || part.contains(candidateArtist)) {
                    score += 20;
                }
            }
        }
        
        // 专辑匹配（如果都有专辑信息）
        if (query.album != null && !query.album.isEmpty() && 
            candidate.album != null && !candidate.album.isEmpty()) {
            String queryAlbum = query.album.toLowerCase();
            String candidateAlbum = candidate.album.toLowerCase();
            
            if (queryAlbum.equals(candidateAlbum)) {
                score += 100;
            } else if (queryAlbum.contains(candidateAlbum) || candidateAlbum.contains(queryAlbum)) {
                score += 50;
            } else {
                score -= 30; // 专辑不匹配，扣分
            }
        } else if (query.album == null || query.album.isEmpty()) {
            if (candidate.album != null && !candidate.album.isEmpty()) {
                score -= 10; // 查询没有专辑但候选有，轻微扣分
            }
        }
        
        return score;
    }
    
    /**
     * 计算匹配分数（兼容旧接口，返回0-1的浮点数）
     */
    public static double score(Query query, Candidate candidate) {
        // 使用新的整数评分算法，然后转换为0-1范围
        int intScore = scoreInt(query, candidate);
        // 将整数分数转换为0-1范围（假设最大分数约为500）
        return Math.max(0.0, Math.min(1.0, intScore / 500.0));
    }
    
    /**
     * 清理标题（去除括号内容、特殊字符等）
     */
    public static String cleanTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        
        String cleaned = title.trim();
        
        // 去除括号及其内容
        cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
        cleaned = cleaned.replaceAll("\\[^\\]]*\\]", "");
        cleaned = cleaned.replaceAll("\\{[^}]*\\}", "");
        
        // 去除常见后缀
        cleaned = cleaned.replaceAll("\\s*-\\s*(Remix|Mix|Extended|Version|Edit|Radio|Acoustic|Live|Demo|Instrumental|Cover|Original|Explicit|Clean)$", "");
        cleaned = cleaned.replaceAll("\\s*\\(Remix|Mix|Extended|Version|Edit|Radio|Acoustic|Live|Demo|Instrumental|Cover|Original|Explicit|Clean\\)$", "");
        
        // 去除特殊字符，但保留中英文、数字和基本标点
        cleaned = cleaned.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-\\'\\']", " ");
        
        // 去除多余空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }
    
    /**
     * 清理艺术家名称
     */
    public static String cleanArtist(String artist) {
        if (artist == null || artist.isEmpty()) {
            return "";
        }
        
        String cleaned = artist.trim();
        
        // 处理常见的合作标记
        cleaned = cleaned.replaceAll("\\s*feat\\.?\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*ft\\.?\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*&\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*,\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*\\+\\s*", " ");
        cleaned = cleaned.replaceAll("\\s*\\|\\s*", " ");
        
        // 去除括号内容
        cleaned = cleaned.replaceAll("\\([^)]*\\)", "");
        cleaned = cleaned.replaceAll("\\[^\\]]*\\]", "");
        
        // 去除特殊字符
        cleaned = cleaned.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-\\'\\']", " ");
        
        // 去除多余空格
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        
        return cleaned;
    }
    
    /**
     * V3.16: 生成搜索关键词列表（基于小张桌面的实现）
     * 生成多种搜索关键词组合，提高匹配成功率
     */
    public static List<String> generateSearchKeywords(String title, String artist) {
        List<String> keywords = new ArrayList<>();
        
        if (title == null) title = "";
        if (artist == null) artist = "";
        
        String cleanTitle = cleanTitle(title);
        String cleanArtist = cleanArtist(artist);
        
        // 基础关键词
        keywords.add(title);
        if (!cleanTitle.equals(title)) {
            keywords.add(cleanTitle);
        }
        
        // 标题 + 艺术家
        if (artist.length() > 0) {
            keywords.add(title + " " + artist);
            if (!cleanTitle.equals(title) || !cleanArtist.equals(artist)) {
                keywords.add(cleanTitle + " " + cleanArtist);
            }
        }
        
        // 艺术家 + 标题
        if (artist.length() > 0) {
            keywords.add(artist + " " + title);
            if (!cleanTitle.equals(title) || !cleanArtist.equals(artist)) {
                keywords.add(cleanArtist + " " + cleanTitle);
            }
        }
        
        // 处理艺术家分割（多个艺术家）
        if (artist.length() > 0) {
            String[] artistParts = artist.split("[,&、/]");
            List<String> validArtists = new ArrayList<>();
            for (String part : artistParts) {
                part = part.trim();
                if (part.length() > 0) {
                    validArtists.add(part);
                }
            }
            
            if (validArtists.size() > 0) {
                // 第一个艺术家 + 标题
                keywords.add(validArtists.get(0) + " " + title);
                if (!cleanTitle.equals(title)) {
                    keywords.add(validArtists.get(0) + " " + cleanTitle);
                }
                
                // 标题 + 第一个艺术家
                keywords.add(title + " " + validArtists.get(0));
                if (!cleanTitle.equals(title)) {
                    keywords.add(cleanTitle + " " + validArtists.get(0));
                }
            }
        }
        
        // 去重并过滤空字符串
        Set<String> uniqueKeywords = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword != null && keyword.trim().length() > 1) {
                uniqueKeywords.add(keyword.trim());
            }
        }
        
        return new ArrayList<>(uniqueKeywords);
    }
}

