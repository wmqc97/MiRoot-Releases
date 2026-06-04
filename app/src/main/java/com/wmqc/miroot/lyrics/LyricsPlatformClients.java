package com.wmqc.miroot.lyrics;

import android.content.Context;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 各音乐平台搜歌 / 取词实现（酷狗、网易、QQ、酷我）。
 */
final class LyricsPlatformClients {

  private static final String TAG = "LyricsPlatformClients";
  private static final int KEYWORD_MAX_ATTEMPTS = 3;

  private LyricsPlatformClients() {}

  static List<LyricsMatcher.Candidate> search(
      Context context,
      String provider,
      String title,
      String artist,
      String album,
      double duration) {
    if (provider == null || provider.isEmpty()) {
      return searchKugou(context, title, artist, album, duration);
    }
    switch (provider) {
      case LyricsApiEndpoints.PROVIDER_NETEASE:
        return searchNetease(context, title, artist, album, duration);
      case LyricsApiEndpoints.PROVIDER_QQ:
        return searchQq(context, title, artist, album, duration);
      case LyricsApiEndpoints.PROVIDER_KUWO:
        return searchKuwo(context, title, artist, album, duration);
      case LyricsApiEndpoints.PROVIDER_KUGOU:
      default:
        return searchKugou(context, title, artist, album, duration);
    }
  }

  static LyricsAPIClient.LyricsResult fetchById(Context context, String id, String provider) {
    if (provider == null || provider.isEmpty()) {
      return fetchKugouById(context, id);
    }
    switch (provider) {
      case LyricsApiEndpoints.PROVIDER_NETEASE:
        return fetchNeteaseById(context, id);
      case LyricsApiEndpoints.PROVIDER_QQ:
        return fetchQqById(context, id);
      case LyricsApiEndpoints.PROVIDER_KUWO:
        return fetchKuwoById(context, id);
      case LyricsApiEndpoints.PROVIDER_KUGOU:
      default:
        return fetchKugouById(context, id);
    }
  }

  // --- Kugou ---

  static List<LyricsMatcher.Candidate> searchKugou(
      Context context, String title, String artist, String album, double duration) {
    return searchWithKeywords(
        context,
        title,
        artist,
        album,
        duration,
        LyricsApiEndpoints.PROVIDER_KUGOU,
        new KeywordSearch() {
          @Override
          public List<LyricsMatcher.Candidate> search(String keyword, LyricsMatcher.Query query) {
            return searchKugouKeyword(keyword, query);
          }
        });
  }

  private static List<LyricsMatcher.Candidate> searchKugouKeyword(
      String keyword, LyricsMatcher.Query query) {
    List<LyricsMatcher.Candidate> temp = new ArrayList<>();
    try {
      String encodedKeyword = URLEncoder.encode(keyword, "UTF-8");
      String searchUrl =
          LyricsApiEndpoints.KUGOU_SEARCH_URL
              + "?format=json&keyword="
              + encodedKeyword
              + "&page=1&pagesize=30";
      String response = LyricsAPIClient.httpGetPublic(searchUrl);
      if (response == null || response.isEmpty()) {
        return temp;
      }
      String jsonContent = stripJsonpWrapper(response);
      JSONObject jsonResponse = new JSONObject(jsonContent);
      JSONObject data = jsonResponse.optJSONObject("data");
      if (data == null) {
        JSONArray directInfo = jsonResponse.optJSONArray("info");
        if (directInfo != null && directInfo.length() > 0) {
          data = jsonResponse;
        } else {
          return temp;
        }
      }
      JSONArray info = data.optJSONArray("info");
      if (info == null || info.length() == 0) {
        return temp;
      }
      for (int j = 0; j < info.length(); j++) {
        JSONObject song = info.getJSONObject(j);
        String songName = song.optString("songname", "");
        String singerName = song.optString("singername", "");
        String hash = song.optString("hash", "");
        if (hash.isEmpty()) {
          continue;
        }
        LyricsMatcher.Candidate candidate =
            new LyricsMatcher.Candidate(
                hash, songName, singerName, "", 0.0, new String[] {"lrc", "krc"},
                LyricsApiEndpoints.PROVIDER_KUGOU);
        candidate.score = LyricsMatcher.scoreInt(query, candidate);
        temp.add(candidate);
      }
    } catch (Exception e) {
      LogHelper.w(TAG, "酷狗搜索失败: " + keyword, e);
    }
    return temp;
  }

  static LyricsAPIClient.LyricsResult fetchKugouById(Context context, String id) {
    LyricsAPIClient.LyricsResult result = new LyricsAPIClient.LyricsResult();
    result.source = "酷狗音乐";
    if (!LyricsAPIClient.isNetworkAvailablePublic(context)) {
      result.error = "网络不可用";
      return result;
    }
    if (id == null || id.isEmpty()) {
      result.error = "歌词ID（hash）为空";
      return result;
    }
    try {
      String lyricsUrl =
          LyricsApiEndpoints.KUGOU_LYRIC_URL + "?cmd=100&hash=" + id + "&timelength=999999";
      String response = LyricsAPIClient.httpGetPublic(lyricsUrl);
      return parseKugouLyricResponse(result, response);
    } catch (Exception e) {
      result.error = "获取歌词异常: " + e.getMessage();
      LogHelper.e(TAG, result.error, e);
    }
    return result;
  }

  private static LyricsAPIClient.LyricsResult parseKugouLyricResponse(
      LyricsAPIClient.LyricsResult result, String response) {
    if (response == null || response.isEmpty()) {
      result.error = "歌词请求失败";
      return result;
    }
    if (response.trim().startsWith("[")) {
      result.lyrics = response;
      result.success = true;
      return result;
    }
    try {
      JSONObject jsonResponse = new JSONObject(response.trim());
      String lyricsContent = jsonResponse.optString("content", "");
      if (!lyricsContent.isEmpty()) {
        result.lyrics = lyricsContent;
        result.success = true;
        return result;
      }
      result.error = "歌词内容为空";
    } catch (org.json.JSONException e) {
      if (response.length() > 10) {
        result.lyrics = response;
        result.success = true;
      } else {
        result.error = "歌词格式不支持或内容为空";
      }
    }
    return result;
  }

  // --- Netease ---

  static List<LyricsMatcher.Candidate> searchNetease(
      Context context, String title, String artist, String album, double duration) {
    return searchWithKeywords(
        context,
        title,
        artist,
        album,
        duration,
        LyricsApiEndpoints.PROVIDER_NETEASE,
        new KeywordSearch() {
          @Override
          public List<LyricsMatcher.Candidate> search(String keyword, LyricsMatcher.Query query) {
            return searchNeteaseKeyword(keyword, query);
          }
        });
  }

  private static List<LyricsMatcher.Candidate> searchNeteaseKeyword(
      String keyword, LyricsMatcher.Query query) {
    List<LyricsMatcher.Candidate> temp = new ArrayList<>();
    try {
      String encoded = URLEncoder.encode(keyword, "UTF-8");
      String url =
          LyricsApiEndpoints.NETEASE_SEARCH_URL
              + "?s="
              + encoded
              + "&type=1&limit=30&offset=0";
      Map<String, String> headers = neteaseHeaders();
      String response = LyricsAPIClient.httpGetPublic(url, headers);
      if (response == null || response.isEmpty()) {
        return temp;
      }
      JSONObject root = new JSONObject(stripJsonpWrapper(response));
      JSONObject result = root.optJSONObject("result");
      if (result == null) {
        return temp;
      }
      JSONArray songs = result.optJSONArray("songs");
      if (songs == null || songs.length() == 0) {
        return temp;
      }
      for (int i = 0; i < songs.length(); i++) {
        JSONObject song = songs.optJSONObject(i);
        if (song == null) {
          continue;
        }
        long id = song.optLong("id", 0L);
        if (id <= 0L) {
          continue;
        }
        String songName = song.optString("name", "");
        String singerName = joinNeteaseArtists(song.optJSONArray("artists"));
        double durSec = song.optLong("duration", 0L) / 1000.0;
        LyricsMatcher.Candidate candidate =
            new LyricsMatcher.Candidate(
                String.valueOf(id),
                songName,
                singerName,
                "",
                durSec,
                new String[] {"lrc"},
                LyricsApiEndpoints.PROVIDER_NETEASE);
        candidate.score = LyricsMatcher.scoreInt(query, candidate);
        temp.add(candidate);
      }
    } catch (Exception e) {
      LogHelper.w(TAG, "网易云搜索失败: " + keyword, e);
    }
    return temp;
  }

  private static String joinNeteaseArtists(JSONArray artists) {
    if (artists == null || artists.length() == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < artists.length(); i++) {
      JSONObject a = artists.optJSONObject(i);
      if (a == null) {
        continue;
      }
      String name = a.optString("name", "").trim();
      if (name.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(name);
    }
    return sb.toString();
  }

  static LyricsAPIClient.LyricsResult fetchNeteaseById(Context context, String id) {
    LyricsAPIClient.LyricsResult result = new LyricsAPIClient.LyricsResult();
    result.source = "网易云音乐";
    if (!LyricsAPIClient.isNetworkAvailablePublic(context)) {
      result.error = "网络不可用";
      return result;
    }
    if (id == null || id.isEmpty()) {
      result.error = "歌曲 id 为空";
      return result;
    }
    try {
      String url =
          LyricsApiEndpoints.NETEASE_LYRIC_URL + "?id=" + id + "&lv=1&kv=1&tv=-1&os=pc";
      String response = LyricsAPIClient.httpGetPublic(url, neteaseHeaders());
      if (response == null || response.isEmpty()) {
        result.error = "歌词请求失败";
        return result;
      }
      JSONObject json = new JSONObject(stripJsonpWrapper(response));
      JSONObject lrc = json.optJSONObject("lrc");
      String lyric = lrc != null ? lrc.optString("lyric", "") : "";
      if (lyric.isEmpty()) {
        JSONObject klyric = json.optJSONObject("klyric");
        lyric = klyric != null ? klyric.optString("lyric", "") : "";
      }
      lyric = lyric.trim();
      if (lyric.isEmpty()) {
        result.error = "歌词内容为空";
        return result;
      }
      result.lyrics = LyricsAPIClient.toSimplifiedChinesePublic(lyric);
      result.success = true;
    } catch (Exception e) {
      result.error = "获取歌词异常: " + e.getMessage();
      LogHelper.e(TAG, result.error, e);
    }
    return result;
  }

  private static Map<String, String> neteaseHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Referer", "https://music.163.com/");
    headers.put("Origin", "https://music.163.com");
    return headers;
  }

  // --- QQ Music ---

  static List<LyricsMatcher.Candidate> searchQq(
      Context context, String title, String artist, String album, double duration) {
    return searchWithKeywords(
        context,
        title,
        artist,
        album,
        duration,
        LyricsApiEndpoints.PROVIDER_QQ,
        new KeywordSearch() {
          @Override
          public List<LyricsMatcher.Candidate> search(String keyword, LyricsMatcher.Query query) {
            return searchQqKeyword(keyword, query);
          }
        });
  }

  private static List<LyricsMatcher.Candidate> searchQqKeyword(
      String keyword, LyricsMatcher.Query query) {
    List<LyricsMatcher.Candidate> temp = new ArrayList<>();
    try {
      String encoded = URLEncoder.encode(keyword, "UTF-8");
      String url =
          LyricsApiEndpoints.QQ_SEARCH_URL
              + "?w="
              + encoded
              + "&format=json&p=1&n=20&cr=1&new_json=1";
      Map<String, String> headers = qqHeaders();
      String response = LyricsAPIClient.httpGetPublic(url, headers);
      if (response == null || response.isEmpty()) {
        return temp;
      }
      JSONObject root = new JSONObject(stripJsonpWrapper(response));
      JSONObject data = root.optJSONObject("data");
      if (data == null) {
        return temp;
      }
      JSONObject song = data.optJSONObject("song");
      JSONArray list = song != null ? song.optJSONArray("list") : null;
      if (list == null || list.length() == 0) {
        return temp;
      }
      for (int i = 0; i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String mid = item.optString("songmid", item.optString("mid", ""));
        if (mid.isEmpty()) {
          continue;
        }
        String songName = item.optString("songname", item.optString("title", ""));
        String singerName = joinQqSingers(item.optJSONArray("singer"));
        double durSec = item.optInt("interval", 0);
        LyricsMatcher.Candidate candidate =
            new LyricsMatcher.Candidate(
                mid,
                songName,
                singerName,
                "",
                durSec,
                new String[] {"lrc"},
                LyricsApiEndpoints.PROVIDER_QQ);
        candidate.score = LyricsMatcher.scoreInt(query, candidate);
        temp.add(candidate);
      }
    } catch (Exception e) {
      LogHelper.w(TAG, "QQ音乐搜索失败: " + keyword, e);
    }
    return temp;
  }

  private static String joinQqSingers(JSONArray singers) {
    if (singers == null || singers.length() == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < singers.length(); i++) {
      JSONObject s = singers.optJSONObject(i);
      if (s == null) {
        continue;
      }
      String name = s.optString("name", "").trim();
      if (name.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('/');
      }
      sb.append(name);
    }
    return sb.toString();
  }

  static LyricsAPIClient.LyricsResult fetchQqById(Context context, String songMid) {
    LyricsAPIClient.LyricsResult result = new LyricsAPIClient.LyricsResult();
    result.source = "QQ音乐";
    if (!LyricsAPIClient.isNetworkAvailablePublic(context)) {
      result.error = "网络不可用";
      return result;
    }
    if (songMid == null || songMid.isEmpty()) {
      result.error = "songmid 为空";
      return result;
    }
    try {
      String url =
          LyricsApiEndpoints.QQ_LYRIC_URL
              + "?format=json&nobase64=1&songmid="
              + URLEncoder.encode(songMid, "UTF-8")
              + "&g_tk=5381";
      String response = LyricsAPIClient.httpGetPublic(url, qqHeaders());
      if (response == null || response.isEmpty()) {
        result.error = "歌词请求失败";
        return result;
      }
      JSONObject json = new JSONObject(stripJsonpWrapper(response));
      String lyric = json.optString("lyric", "");
      if (lyric.isEmpty()) {
        lyric = json.optString("lrc", "");
      }
      lyric = lyric.trim();
      if (lyric.isEmpty()) {
        result.error = "歌词内容为空";
        return result;
      }
      result.lyrics = LyricsAPIClient.toSimplifiedChinesePublic(lyric);
      result.success = true;
    } catch (Exception e) {
      result.error = "获取歌词异常: " + e.getMessage();
      LogHelper.e(TAG, result.error, e);
    }
    return result;
  }

  private static Map<String, String> qqHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("Referer", "https://y.qq.com/");
    return headers;
  }

  // --- Kuwo ---

  static List<LyricsMatcher.Candidate> searchKuwo(
      Context context, String title, String artist, String album, double duration) {
    return searchWithKeywords(
        context,
        title,
        artist,
        album,
        duration,
        LyricsApiEndpoints.PROVIDER_KUWO,
        new KeywordSearch() {
          @Override
          public List<LyricsMatcher.Candidate> search(String keyword, LyricsMatcher.Query query) {
            return searchKuwoKeyword(keyword, query);
          }
        });
  }

  private static List<LyricsMatcher.Candidate> searchKuwoKeyword(
      String keyword, LyricsMatcher.Query query) {
    List<LyricsMatcher.Candidate> temp = new ArrayList<>();
    try {
      String encoded = URLEncoder.encode(keyword, "UTF-8");
      String url =
          LyricsApiEndpoints.KUWO_SEARCH_URL
              + "?all="
              + encoded
              + "&ft=music&rformat=json&encoding=utf8&rn=30";
      String response = LyricsAPIClient.httpGetPublic(url);
      if (response == null || response.isEmpty()) {
        return temp;
      }
      JSONObject root = new JSONObject(stripJsonpWrapper(response));
      JSONArray abslist = root.optJSONArray("abslist");
      if (abslist == null || abslist.length() == 0) {
        return temp;
      }
      for (int i = 0; i < abslist.length(); i++) {
        JSONObject item = abslist.optJSONObject(i);
        if (item == null) {
          continue;
        }
        String musicId = kuwoMusicIdFromItem(item);
        if (musicId.isEmpty()) {
          continue;
        }
        String songName = item.optString("SONGNAME", item.optString("name", ""));
        String singerName = item.optString("ARTIST", item.optString("artist", ""));
        LyricsMatcher.Candidate candidate =
            new LyricsMatcher.Candidate(
                musicId,
                songName,
                singerName,
                "",
                0.0,
                new String[] {"lrc"},
                LyricsApiEndpoints.PROVIDER_KUWO);
        candidate.score = LyricsMatcher.scoreInt(query, candidate);
        temp.add(candidate);
      }
    } catch (Exception e) {
      LogHelper.w(TAG, "酷我搜索失败: " + keyword, e);
    }
    return temp;
  }

  private static String kuwoMusicIdFromItem(JSONObject item) {
    String rid = item.optString("MUSICRID", item.optString("rid", ""));
    if (rid.isEmpty()) {
      rid = item.optString("DC_TARGETID", "");
    }
    if (rid.startsWith("SONG_")) {
      return rid.substring(5);
    }
    return rid;
  }

  static LyricsAPIClient.LyricsResult fetchKuwoById(Context context, String musicId) {
    LyricsAPIClient.LyricsResult result = new LyricsAPIClient.LyricsResult();
    result.source = "酷我音乐";
    if (!LyricsAPIClient.isNetworkAvailablePublic(context)) {
      result.error = "网络不可用";
      return result;
    }
    if (musicId == null || musicId.isEmpty()) {
      result.error = "musicId 为空";
      return result;
    }
    try {
      String url = LyricsApiEndpoints.KUWO_LYRIC_URL + "?musicId=" + musicId;
      String response = LyricsAPIClient.httpGetPublic(url);
      if (response == null || response.isEmpty()) {
        result.error = "歌词请求失败";
        return result;
      }
      String lyric = extractKuwoLyricText(response);
      if (lyric == null || lyric.trim().isEmpty()) {
        result.error = "歌词内容为空";
        return result;
      }
      result.lyrics = LyricsAPIClient.toSimplifiedChinesePublic(lyric.trim());
      result.success = true;
    } catch (Exception e) {
      result.error = "获取歌词异常: " + e.getMessage();
      LogHelper.e(TAG, result.error, e);
    }
    return result;
  }

  private static String extractKuwoLyricText(String response) throws org.json.JSONException {
    JSONObject root = new JSONObject(stripJsonpWrapper(response));
    JSONObject data = root.optJSONObject("data");
    if (data != null) {
      String lrc = data.optString("lrclist", "");
      if (!lrc.isEmpty() && lrc.contains("[")) {
        return lrc;
      }
      JSONArray lrclist = data.optJSONArray("lrclist");
      if (lrclist != null && lrclist.length() > 0) {
        return buildLrcFromKuwoList(lrclist);
      }
      String content = data.optString("lrc", data.optString("content", ""));
      if (!content.isEmpty()) {
        return content;
      }
    }
    String topLrc = root.optString("lrc", root.optString("content", ""));
    if (!topLrc.isEmpty()) {
      return topLrc;
    }
    if (response.trim().startsWith("[")) {
      return response;
    }
    return "";
  }

  private static String buildLrcFromKuwoList(JSONArray lrclist) throws org.json.JSONException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lrclist.length(); i++) {
      JSONObject line = lrclist.optJSONObject(i);
      if (line == null) {
        continue;
      }
      String time = line.optString("time", line.optString("lineLyricTime", ""));
      String text = line.optString("lineLyric", line.optString("content", ""));
      if (text.isEmpty()) {
        continue;
      }
      if (!time.isEmpty() && !time.startsWith("[")) {
        sb.append('[').append(time).append(']').append(text);
      } else if (time.startsWith("[")) {
        sb.append(time).append(text);
      } else {
        sb.append(text);
      }
      sb.append('\n');
    }
    return sb.toString();
  }

  // --- shared ---

  private interface KeywordSearch {
    List<LyricsMatcher.Candidate> search(String keyword, LyricsMatcher.Query query);
  }

  private static List<LyricsMatcher.Candidate> searchWithKeywords(
      Context context,
      String title,
      String artist,
      String album,
      double duration,
      String providerLabel,
      KeywordSearch searcher) {
    List<LyricsMatcher.Candidate> candidates = new ArrayList<>();
    if (title == null || title.isEmpty()) {
      return candidates;
    }
    if (!LyricsAPIClient.isNetworkAvailablePublic(context)) {
      return candidates;
    }
    if (artist == null) {
      artist = "";
    }
    LyricsMatcher.Query query =
        new LyricsMatcher.Query(title, artist, album != null ? album : "", duration);
    List<String> keywords = LyricsMatcher.generateSearchKeywords(title.trim(), artist.trim());
    if (keywords.isEmpty()) {
      keywords = new ArrayList<>();
      keywords.add(title.trim());
    }
    int maxKeywords = Math.min(KEYWORD_MAX_ATTEMPTS, keywords.size());
    for (int i = 0; i < maxKeywords; i++) {
      String keyword = keywords.get(i);
      List<LyricsMatcher.Candidate> temp = searcher.search(keyword, query);
      sortAndPickCandidates(temp, query, candidates, i);
      if (!candidates.isEmpty()) {
        break;
      }
    }
    if (candidates.isEmpty()) {
      LogHelper.w(TAG, providerLabel + " 搜索无匹配: " + title + " - " + artist);
    }
    return candidates;
  }

  private static void sortAndPickCandidates(
      List<LyricsMatcher.Candidate> temp,
      LyricsMatcher.Query query,
      List<LyricsMatcher.Candidate> out,
      int keywordIndex) {
    if (temp.size() > 1) {
      Collections.sort(
          temp,
          new Comparator<LyricsMatcher.Candidate>() {
            @Override
            public int compare(LyricsMatcher.Candidate a, LyricsMatcher.Candidate b) {
              return Integer.compare((int) b.score, (int) a.score);
            }
          });
    }
    int scoreThreshold = keywordIndex == 0 ? 10 : 20;
    for (LyricsMatcher.Candidate candidate : temp) {
      if (candidate.score >= scoreThreshold) {
        out.add(candidate);
        if (out.size() >= 3) {
          break;
        }
      }
    }
  }

  /** 酷狗与 QQ 等接口可能返回 JSONP，剥离外层括号 / callback。 */
  static String stripJsonpWrapper(String raw) {
    if (raw == null) {
      return "";
    }
    String jsonContent = raw.trim();
    if (jsonContent.startsWith("(") && jsonContent.endsWith(")")) {
      jsonContent = jsonContent.substring(1, jsonContent.length() - 1);
    }
    if (jsonContent.startsWith("callback(") && jsonContent.endsWith(")")) {
      int start = jsonContent.indexOf('(') + 1;
      int end = jsonContent.lastIndexOf(')');
      if (start > 0 && end > start) {
        jsonContent = jsonContent.substring(start, end);
      }
    }
    int jsonStart = jsonContent.indexOf('{');
    int arrStart = jsonContent.indexOf('[');
    int start = -1;
    if (jsonStart >= 0 && arrStart >= 0) {
      start = Math.min(jsonStart, arrStart);
    } else if (jsonStart >= 0) {
      start = jsonStart;
    } else if (arrStart >= 0) {
      start = arrStart;
    }
    if (start > 0) {
      jsonContent = jsonContent.substring(start);
    }
    return jsonContent.trim();
  }
}
