package com.wmqc.miroot.lyrics;

/**
 * 第三方歌词网络 API 基址（搜歌 / 取词）。
 * 各平台完整 URL 由 {@link LyricsAPIClient} 拼装查询参数。
 */
public final class LyricsApiEndpoints {

  // 酷狗音乐 API
  public static final String KUGOU_SEARCH_URL =
      "https://mobilecdn.kugou.com/api/v3/search/song";
  public static final String KUGOU_LYRIC_URL =
      "https://m.kugou.com/app/i/krc.php";

  // 网易云音乐 API
  public static final String NETEASE_SEARCH_URL =
      "https://music.163.com/api/search/get/web";
  public static final String NETEASE_LYRIC_URL =
      "https://music.163.com/api/song/lyric";

  // QQ 音乐 API
  public static final String QQ_SEARCH_URL =
      "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
  public static final String QQ_LYRIC_URL =
      "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg";

  // 酷我音乐 API（手机版；车机版 {@code cn.kuwo.kwmusiccar} 仍优先广播 / AUDIO_LYRIC）
  public static final String KUWO_SEARCH_URL = "http://search.kuwo.cn/r.s";
  public static final String KUWO_LYRIC_URL =
      "http://m.kuwo.cn/newh5/singles/songinfoandlrc";

  public static final String PROVIDER_KUGOU = "kugou";
  public static final String PROVIDER_NETEASE = "netease";
  public static final String PROVIDER_QQ = "qq";
  public static final String PROVIDER_KUWO = "kuwo";

  private LyricsApiEndpoints() {}
}
