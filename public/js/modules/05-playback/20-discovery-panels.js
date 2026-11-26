// public/js/modules/05-playback/20-discovery-panels.js
// 每日推荐面板 + 历史播放面板。
//
// 为什么要有这两个面板：
//   1) 「每日推荐」原来点了会跑 runHomeSearch('每日推荐')，把关键词塞进搜索框去搜——
//      完全是错的。现在改成弹出一个与「壁纸库」同尺寸的大面板，把各平台的推荐歌
//      曲都放进去，往下滑就继续加载 / 刷新。
//   2) 「最近播放」原来点一下只取 listenStatsState.history[0] 单曲直接播，
//      不要再盲播，改成弹出面板列出最近 20 首历史，点了才播（并整批进队列）。
//
// 面板 DOM 全部在这里动态创建，不依赖 index.html 的改动。

(function (root) {
  'use strict';

  var PANEL_STYLE_ID = 'discovery-panel-style';
  var DAILY_PANEL_ID = 'daily-recommend-panel';
  var HISTORY_PANEL_ID = 'listen-history-panel';
  var HISTORY_FIRST_PAGE = 20;
  var HISTORY_PAGE_SIZE = 20;
  var HISTORY_MAX = 180;

  // 平台顺序：先网易云（用户默认看这个），再其它
  var DAILY_SOURCES = ['netease', 'qishui', 'kugou', 'qq', 'bodian', 'ytm'];
  var DAILY_PAGE_SIZE = 12;
  // 「总平台」换一批要一次凑满 20 首，并按平台平均分配：
  //   人均 = 20 / 平台数（5 个平台 → 各 4 首），每个平台至少请求 4 首左右，
  //   某个平台失败/没歌时再从别的平台补一轮。
  var DAILY_BALANCE_TOTAL = 20;
  var DAILY_MIN_PER_SOURCE = 4;

  var SOURCE_LABEL = {
    netease: '网易云音乐',
    qishui: '汽水音乐',
    kugou: '酷狗音乐',
    qq: 'QQ 音乐',
    bodian: '波点音乐',
    ytm: 'YouTube Music',
    spotify: 'Spotify'
  };
  var SOURCE_FEED_ENDPOINT = {
    qishui: '/api/qishui/feed',
    kugou: '/api/kugou/recommendations',
    qq: '/api/qq/recommend/radar',
    bodian: '/api/bodian/feed',
    ytm: '/api/ytm/feed',
    spotify: '/api/spotify/feed'
  };

  var dailyState = {
    open: false,
    loading: false,
    round: 0,
    seen: null,
    songs: [],
    failedSources: [],
    busy: false,
    // 平台筛选：'all' = 总平台（默认），其它值是 DAILY_SOURCES 里的平台 key
    sourceFilter: 'all',
    // 当前「看得见」的歌（过滤后的），点播放按这个数组取下标
    visible: []
  };
  var historyState = { open: false, limit: HISTORY_FIRST_PAGE, expanded: false };

  // ---------- 基础 ----------
  function esc(value) {
    return typeof escHtml === 'function'
      ? escHtml(String(value == null ? '' : value))
      : String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
      });
  }

  function sourceLabel(source) {
    return SOURCE_LABEL[source] || '当前平台';
  }

  function injectStyle() {
    if (document.getElementById(PANEL_STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = PANEL_STYLE_ID;
    style.textContent = [
      /* 面板本体沿用壁纸库的尺寸（online-wallpaper-modal），这里只补列表与卡片样式 */
      '.discovery-panel-body{flex:1 1 auto;min-height:0;display:flex;flex-direction:column;margin-top:.06rem}',
      '.discovery-panel-list{flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain;',
      '  -webkit-overflow-scrolling:touch;padding:.02rem .04rem .18rem;scrollbar-width:thin;',
      '  scrollbar-color:rgba(255,255,255,.18) transparent}',
      '.discovery-panel-section{display:flex;align-items:baseline;gap:.1rem;margin:.14rem 0 .06rem;',
      '  font-size:12px;letter-spacing:.06em;color:rgba(255,255,255,.5)}',
      '.discovery-panel-section:first-child{margin-top:0}',
      '.discovery-panel-section strong{font-size:13px;color:rgba(255,255,255,.86);font-weight:600}',
      '.discovery-panel-row{display:grid;grid-template-columns:44px minmax(0,1fr) auto;align-items:center;',
      '  gap:.1rem;padding:.07rem .08rem;border:1px solid rgba(255,255,255,.06);border-radius:.1rem;',
      '  background:rgba(255,255,255,.028);cursor:pointer;transition:background .16s ease,border-color .16s ease;',
      '  margin-bottom:.055rem}',
      '.discovery-panel-row:active,.discovery-panel-row:hover{background:rgba(var(--fc-accent-rgb,122,199,255),.1);',
      '  border-color:rgba(122,199,255,.3)}',
      '.discovery-panel-cover{width:44px;height:44px;border-radius:.08rem;background-size:cover;',
      '  background-position:center;background-color:rgba(255,255,255,.06);flex:0 0 auto}',
      '.discovery-panel-copy{min-width:0}',
      '.discovery-panel-title{font-size:13px;font-weight:600;color:rgba(255,255,255,.93);',
      '  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.discovery-panel-sub{font-size:11.5px;color:rgba(255,255,255,.46);overflow:hidden;',
      '  text-overflow:ellipsis;white-space:nowrap;margin-top:2px}',
      '.discovery-panel-meta{font-size:11px;color:rgba(255,255,255,.38);white-space:nowrap}',
      '.discovery-panel-status{flex:0 0 auto;padding:.06rem 0 0;font-size:11.5px;',
      '  color:rgba(255,255,255,.42);text-align:center}',
      '.discovery-panel-status.error{color:rgba(255,120,120,.85)}',
      '.discovery-panel-empty{padding:.5rem .2rem;text-align:center;font-size:12.5px;',
      '  color:rgba(255,255,255,.36);line-height:1.6}',
      '.discovery-panel-loading{padding:.3rem .2rem;text-align:center;font-size:12px;color:rgba(255,255,255,.4)}',
      '.discovery-panel-foot{flex:0 0 auto;display:flex;gap:.1rem;padding-top:.08rem}',
      '.discovery-panel-foot button{flex:1 1 auto}',
      /* 历史播放条目时间 */
      '.discovery-panel-when{font-size:10.5px;color:rgba(255,255,255,.3);white-space:nowrap}'
    ].join('\n');
    document.head.appendChild(style);
  }

  function closeIconButton(label) {
    return '<button class="wallpaper-engine-close" type="button" aria-label="关闭' + esc(label) + '">×</button>';
  }

  function buildPanelShell(id, kicker, title, closeLabel) {
    var mask = document.createElement('div');
    mask.id = id;
    mask.className = 'modal-mask';
    mask.setAttribute('role', 'dialog');
    mask.setAttribute('aria-modal', 'true');
    mask.innerHTML =
      '<div class="modal online-wallpaper-modal discovery-panel-modal">' +
      '<div class="online-wp-header"><div>' +
      '<div class="online-wp-kicker">' + esc(kicker) + '</div>' +
      '<h2>' + esc(title) + '</h2>' +
      '</div>' + closeIconButton(closeLabel) + '</div>' +
      '<div class="discovery-panel-body">' +
      // 每日推荐面板顶部加一排平台筛选 chip（总平台默认选中），位置在列表上方
      (id === DAILY_PANEL_ID
        ? '<div class="discovery-panel-tabs">' + dailyTabsHtml() + '</div>'
        : '') +
      '<div class="discovery-panel-list"></div>' +
      '<div class="discovery-panel-status"></div>' +
      (id === DAILY_PANEL_ID
        ? '<div class="discovery-panel-foot"><button class="fx-mini-btn ghost" type="button" data-daily-refresh>换一批</button>' +
          '<button class="fx-mini-btn" type="button" data-daily-playall>全部加入队列</button></div>'
        : '') +
      '</div></div>';
    document.body.appendChild(mask);
    mask.addEventListener('click', function (event) {
      if (event.target === mask) closePanel(id);
    });
    return mask;
  }

  function closePanel(id) {
    var mask = document.getElementById(id);
    if (mask) mask.classList.remove('show');
    if (id === DAILY_PANEL_ID) dailyState.open = false;
    if (id === HISTORY_PANEL_ID) historyState.open = false;
  }

  // ---------- 历史播放面板 ----------
  function listenHistoryList() {
    var history = (typeof listenStatsState !== 'undefined' && listenStatsState && listenStatsState.history) || [];
    return history.filter(function (item) { return item && item.name; });
  }

  function historyRecordToSong(record) {
    if (!record) return null;
    var source = record.sourceKey || record.provider || record.resolvedPlaybackProvider || '';
    if (!source) source = record.mid ? 'qq' : (record.hash ? 'kugou' : (record.spotifyId ? 'spotify' : 'netease'));
    var song = {
      provider: source,
      source: source,
      type: record.type || (source === 'qq' ? 'qq' : 'song'),
      id: record.id || '',
      name: record.name || '未知歌曲',
      artist: record.artist || '',
      cover: record.cover || '',
      albumId: record.albumId || '',
      mid: record.mid || '',
      songmid: record.mid || '',
      mediaMid: record.mediaMid || '',
      hash: record.hash || '',
      mixSongId: record.mixSongId || '',
      providerSongId: record.providerSongId || '',
      spotifyId: record.spotifyId || '',
      uri: record.uri || ''
    };
    // 有些平台（QQ / 汽水）只有 mid / uri 才够路由播放
    if (source === 'qq' && !song.mid && song.id) song.mid = String(song.id);
    return typeof cloneSong === 'function' ? cloneSong(song) : song;
  }

  function historyWhenLabel(record) {
    var at = Number(record && record.playedAt) || 0;
    if (!at) return '';
    var diff = Date.now() - at;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    var day = Math.floor(diff / 86400000);
    if (day < 30) return day + ' 天前';
    var d = new Date(at);
    return (d.getMonth() + 1) + '月' + d.getDate() + '日';
  }

  function historyCoverOf(path) {
    try {
      if (typeof songCoverSrc === 'function') {
        var url = songCoverSrc(historyRecordToSong(path), 96);
        if (url) return url;
      }
    } catch (e) { /* 忽略 */ }
    return path.cover || '';
  }

  function renderHistoryPanel() {
    var mask = document.getElementById(HISTORY_PANEL_ID);
    if (!mask) return;
    var list = mask.querySelector('.discovery-panel-list');
    var status = mask.querySelector('.discovery-panel-status');
    var records = listenHistoryList();
    if (!records.length) {
      if (list) {
        list.innerHTML = '<div class="discovery-panel-empty">还没有历史播放记录。<br>播放几首歌后，最近听过的 20 首会出现在这里。</div>';
      }
      if (status) status.textContent = '';
      return;
    }
    var shown = records.slice(0, historyState.limit);
    var rows = [];
    for (var i = 0; i < shown.length; i += 1) {
      var record = shown[i];
      var cover = historyCoverOf(record);
      var coverStyle = cover ? ' style="background-image:url(&quot;' + esc(cover) + '&quot;)"' : '';
      rows.push(
        '<div class="discovery-panel-row" role="button" tabindex="0" data-history-index="' + i + '">' +
        '<div class="discovery-panel-cover"' + coverStyle + '></div>' +
        '<div class="discovery-panel-copy">' +
        '<div class="discovery-panel-title">' + esc(record.name || '未知歌曲') + '</div>' +
        '<div class="discovery-panel-sub">' + esc(record.artist || '未知歌手') +
        (record.sourceKey || record.provider ? ' · ' + esc(sourceLabel(record.sourceKey || record.provider)) : '') +
        '</div></div>' +
        '<div class="discovery-panel-when">' + esc(historyWhenLabel(record)) + '</div>' +
        '</div>'
      );
    }
    if (list) list.innerHTML = rows.join('');
    if (status) {
      var rest = records.length - shown.length;
      status.className = 'discovery-panel-status';
      status.textContent = rest > 0
        ? '已显示最近 ' + shown.length + ' 首，继续往下滑载入更早的记录'
        : '共 ' + records.length + ' 条历史播放记录';
    }
  }

  function playHistoryAt(index) {
    var records = listenHistoryList().slice(0, historyState.limit);
    var record = records[index];
    if (!record) return;
    var songs = [];
    for (var i = 0; i < records.length; i += 1) {
      var rebuilt = historyRecordToSong(records[i]);
      if (rebuilt && (rebuilt.id || rebuilt.mid || rebuilt.hash || rebuilt.name)) songs.push(rebuilt);
    }
    if (!songs.length) {
      if (typeof showToast === 'function') showToast('这些历史记录缺少可播放的音源标识');
      return;
    }
    var playIndex = Math.max(0, Math.min(songs.length - 1, index));
    closePanel(HISTORY_PANEL_ID);
    root.playQueue = songs;
    root.currentIdx = playIndex;
    if (typeof safeRenderQueuePanel === 'function') safeRenderQueuePanel('listen-history', { scrollCurrent: true });
    if (typeof safeShelfRebuild === 'function') safeShelfRebuild('listen-history', true);
    if (typeof forcePlaybackControlsInteractive === 'function') forcePlaybackControlsInteractive();
    Promise.resolve(root.playQueueAt(playIndex, {
      manual: true,
      context: { type: 'listen-history', playlistName: '最近播放' }
    })).catch(function (error) { console.warn('[ListenHistory] play failed:', error); });
  }

  function openListenHistoryPanel() {
    injectStyle();
    var mask = document.getElementById(HISTORY_PANEL_ID) || buildPanelShell(HISTORY_PANEL_ID, 'RECENT PLAYS', '最近播放', '最近播放');
    historyState.open = true;
    historyState.limit = HISTORY_FIRST_PAGE;
    historyState.expanded = false;
    mask.classList.add('show');
    renderHistoryPanel();
    bindHistoryEvents(mask);
    return true;
  }

  var historyEventsBound = false;
  function bindHistoryEvents(mask) {
    if (historyEventsBound) return;
    historyEventsBound = true;
    var list = mask.querySelector('.discovery-panel-list');
    mask.querySelector('.wallpaper-engine-close').addEventListener('click', function () {
      closePanel(HISTORY_PANEL_ID);
    });
    list.addEventListener('click', function (event) {
      var row = event.target.closest('[data-history-index]');
      if (!row) return;
      playHistoryAt(Number(row.getAttribute('data-history-index')) || 0);
    });
    list.addEventListener('keydown', function (event) {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      var row = event.target.closest('[data-history-index]');
      if (!row) return;
      event.preventDefault();
      playHistoryAt(Number(row.getAttribute('data-history-index')) || 0);
    });
    // 往下滑到底 → 载入更早的记录
    list.addEventListener('scroll', function () {
      if (!historyState.open) return;
      if (list.scrollTop + list.clientHeight < list.scrollHeight - 80) return;
      var records = listenHistoryList();
      if (historyState.limit >= Math.min(HISTORY_MAX, records.length)) {
        historyState.limit = Math.min(HISTORY_MAX, records.length);
        return;
      }
      historyState.limit = Math.min(
        Math.min(HISTORY_MAX, records.length),
        historyState.limit + HISTORY_PAGE_SIZE
      );
      renderHistoryPanel();
    }, { passive: true });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && historyState.open) closePanel(HISTORY_PANEL_ID);
    });
  }

  // ---------- 每日推荐面板 ----------
  async function fetchSourceSongs(source, limit, force) {
    if (source === 'netease') {
      // 网易云每日推荐走已有的 discover 接口，避免重复实现
      if (typeof loadHomeDiscover === 'function' && (force || !root.homeDiscoverState || !root.homeDiscoverState.loaded)) {
        await loadHomeDiscover(!!force);
      }
      var neteaseSongs = (root.homeDiscoverState && root.homeDiscoverState.songs) || [];
      return neteaseSongs.slice(0, limit);
    }
    var endpoint = SOURCE_FEED_ENDPOINT[source];
    if (!endpoint) return [];
    var url = endpoint + (endpoint.indexOf('?') >= 0 ? '&' : '?') + 'limit=' + limit + '&t=' + Date.now();
    var data = await apiJson(url, { timeoutMs: 15000 });
    if (data && data.error) {
      var authLike = /(?:AUTH|LOGIN)_REQUIRED|NOT_CONFIGURED|NOT_LOGGED_IN/i.test(String(data.error));
      if (authLike) return [];
      throw new Error(String(data.error));
    }
    var raw = data && (data.songs || data.tracks || data.items || data.recommendations || data.list);
    return (Array.isArray(raw) ? raw : []).slice(0, limit);
  }

  function dailySeenSet() {
    if (!dailyState.seen) dailyState.seen = Object.create(null);
    return dailyState.seen;
  }

  function setDailySeen(next) {
    dailyState.seen = next || Object.create(null);
  }

  function dailyStatus(text, isError) {
    var mask = document.getElementById(DAILY_PANEL_ID);
    if (!mask) return;
    var status = mask.querySelector('.discovery-panel-status');
    if (!status) return;
    status.className = 'discovery-panel-status' + (isError ? ' error' : '');
    status.textContent = text || '';
  }

  // ---------- 平台筛选 chip（总平台 / 各平台） ----------
  function dailyTabsHtml() {
    // 按 DAILY_SOURCES 里真实支持的平台生成，label 复用 sourceLabel()，避免各面板叫法不一致
    var html = '<button class="discovery-panel-tab active" type="button" data-daily-source="all" aria-pressed="true">总平台</button>';
    DAILY_SOURCES.forEach(function (source) {
      html += '<button class="discovery-panel-tab" type="button" data-daily-source="' + esc(source) + '" aria-pressed="false">' +
        esc(sourceLabel(source)) + '</button>';
    });
    return html;
  }

  function updateDailyTabs(mask) {
    if (!mask) mask = document.getElementById(DAILY_PANEL_ID);
    if (!mask) return;
    var tabs = mask.querySelectorAll('.discovery-panel-tab');
    for (var i = 0; i < tabs.length; i += 1) {
      var active = (tabs[i].getAttribute('data-daily-source') || 'all') === (dailyState.sourceFilter || 'all');
      tabs[i].classList.toggle('active', active);
      tabs[i].setAttribute('aria-pressed', active ? 'true' : 'false');
    }
  }

  // 当前「看得见」的歌：总平台 = 全部缓存，选平台 = 只留 song.__dailySource 等于该平台的
  // 注意这可能是个新数组，播放必须按它取下标，不能用过滤前的全局下标
  function dailyVisibleSongs() {
    if (dailyState.sourceFilter === 'all') return dailyState.songs;
    return dailyState.songs.filter(function (song) {
      return song && song.__dailySource === dailyState.sourceFilter;
    });
  }

  function dailyRowHtml(song, index, source) {
    var cover = '';
    try { cover = typeof songCoverSrc === 'function' ? songCoverSrc(song, 96) : (song.cover || ''); } catch (e) { cover = song.cover || ''; }
    var coverStyle = cover ? ' style="background-image:url(&quot;' + esc(cover) + '&quot;)"' : '';
    return '<div class="discovery-panel-row" role="button" tabindex="0" data-daily-index="' + index + '">' +
      '<div class="discovery-panel-cover"' + coverStyle + '></div>' +
      '<div class="discovery-panel-copy">' +
      '<div class="discovery-panel-title">' + esc(song.name || '未知歌曲') + '</div>' +
      '<div class="discovery-panel-sub">' + esc(song.artist || '未知歌手') +
      (song.album ? ' · ' + esc(song.album) : '') + '</div></div>' +
      '<div class="discovery-panel-meta">' + esc(sourceLabel(source)) + '</div>' +
      '</div>';
  }

  function updateDailyStatusLine() {
    var filter = dailyState.sourceFilter || 'all';
    var visible = dailyVisibleSongs();
    if (!visible.length) {
      dailyStatus(dailyState.loading ? '正在读取推荐…' : '暂时没有可显示的推荐歌曲', !dailyState.loading);
      return;
    }
    if (filter !== 'all') {
      var ownFailed = dailyState.failedSources.indexOf(filter) >= 0;
      dailyStatus(sourceLabel(filter) + ' · 已收录 ' + visible.length + ' 首' +
        (ownFailed ? '（这个平台未登录或暂时没有推荐）' : '') + ' · 下滑继续加载', false);
      return;
    }
    var loadedSources = {};
    dailyState.songs.forEach(function (song) { loadedSources[song.__dailySource || 'netease'] = true; });
    var names = Object.keys(loadedSources).map(sourceLabel).join('、');
    var failedNames = dailyState.failedSources.map(sourceLabel);
    dailyStatus('已收录 ' + dailyState.songs.length + ' 首 · ' + (names || '推荐') +
      (failedNames.length ? '（' + failedNames.join('、') + ' 未登录或无推荐）' : '') +
      ' · 下滑继续加载下一批', false);
  }

  function renderDailyPanel() {
    var mask = document.getElementById(DAILY_PANEL_ID);
    if (!mask) return;
    var list = mask.querySelector('.discovery-panel-list');
    if (!list) return;
    updateDailyTabs(mask);
    var visible = dailyVisibleSongs();
    dailyState.visible = visible;
    if (!visible.length) {
      var scope = dailyState.sourceFilter === 'all' ? '各平台' : sourceLabel(dailyState.sourceFilter);
      list.innerHTML = dailyState.loading
        ? '<div class="discovery-panel-loading">正在读取' + esc(scope) + '每日推荐…</div>'
        : '<div class="discovery-panel-empty">' + esc(scope) + '暂时没有返回推荐歌曲。<br>' +
          (dailyState.sourceFilter === 'all'
            ? '请确认至少有一个平台已登录，再点「换一批」重试。'
            : '点「换一批」再试一次，或者切回「总平台」看看别的平台。') + '</div>';
      return;
    }
    var html = [];
    if (dailyState.sourceFilter !== 'all') {
      // 单平台：一个列表就够了，不用再分组；右侧平台名照样显示
      html.push('<div class="discovery-panel-section"><strong>' + esc(sourceLabel(dailyState.sourceFilter)) +
        '</strong><span>每日推荐 · ' + visible.length + ' 首</span></div>');
      visible.forEach(function (song, index) {
        html.push(dailyRowHtml(song, index, dailyState.sourceFilter));
      });
    } else {
      // 总平台：按平台分组，同一个平台的歌聚在一起（原有行为）
      var groups = Object.create(null);
      var order = [];
      visible.forEach(function (song, index) {
        var source = song.__dailySource || 'netease';
        if (!groups[source]) { groups[source] = []; order.push(source); }
        groups[source].push({ song: song, index: index });
      });
      order.forEach(function (source) {
        var items = groups[source];
        html.push('<div class="discovery-panel-section"><strong>' + esc(sourceLabel(source)) + '</strong>' +
          '<span>每日推荐 · ' + items.length + ' 首</span></div>');
        items.forEach(function (entry) {
          html.push(dailyRowHtml(entry.song, entry.index, entry.song.__dailySource || source));
        });
      });
    }
    if (dailyState.loading) html.push('<div class="discovery-panel-loading">正在加载下一批…</div>');
    list.innerHTML = html.join('');
  }

  function playDailyAt(index) {
    // 索引是「当前可见列表」里的下标（单平台过滤后列表会变短），不能拿过滤前的全局 songs 去取，
    // 否则点第一首会播到别的平台、甚至越界不播。
    var songs = dailyVisibleSongs();
    dailyState.visible = songs;
    var song = songs[index];
    if (!song) return;
    var queue = songs.slice();
    closePanel(DAILY_PANEL_ID);
    root.playQueue = queue;
    root.currentIdx = Math.max(0, Math.min(queue.length - 1, index));
    if (typeof safeRenderQueuePanel === 'function') safeRenderQueuePanel('daily-recommend-panel', { scrollCurrent: true });
    if (typeof safeShelfRebuild === 'function') safeShelfRebuild('daily-recommend-panel', true);
    if (typeof forcePlaybackControlsInteractive === 'function') forcePlaybackControlsInteractive();
    Promise.resolve(root.playQueueAt(root.currentIdx, {
      manual: true,
      context: { type: 'daily-recommend', playlistName: '每日推荐' }
    })).catch(function (error) { console.warn('[DailyRecommend] play failed:', error); });
  }

  // ---------- 加载 ----------
  // 把一个候选收进缓存（去重 + 打平台标记），收下了返回 true
  function takeDailyCandidate(source, candidate) {
    if (!candidate) return false;
    var key = typeof queueItemKey === 'function' ? queueItemKey(candidate) : (candidate.id || candidate.name);
    if (!key) return false;
    var seen = dailySeenSet();
    if (seen[key]) return false;
    seen[key] = true;
    var copy = typeof cloneSong === 'function' ? cloneSong(candidate) : candidate;
    copy.__dailySource = source;
    dailyState.songs.push(copy);
    return true;
  }

  // 换一批某个平台时，把它自己的旧歌和去重键都拿掉：
  //   · 旧歌移出缓存 → 这个平台看起来才是「换了一批」
  //   · 去重键删掉 → 新批次里重复的歌不会被自己拦下
  //   · 其它平台的缓存一律不动 → 切回「总平台」不用重新请求
  function dropDailySource(source) {
    var kept = [];
    dailyState.songs.forEach(function (song) {
      if (!song || song.__dailySource !== source) { kept.push(song); return; }
      var key = typeof queueItemKey === 'function' ? queueItemKey(song) : (song.id || song.name);
      if (key && dailyState.seen) delete dailyState.seen[key];
    });
    dailyState.songs = kept;
  }

  // 「总平台」一次凑满 20 首并按平台平均分配：
  //   1) 人均配额 = 20 / 平台数（5 个平台 → 各 4 首），每个平台至少请求 4 首左右；
  //   2) 先按配额各收各的，配额之外多出来的候选先留在 leftovers 里当「补位」；
  //   3) 哪个平台失败/没歌/不够数，就从 leftovers 里按平台轮流补，一家一轮补一首；
  //   4) 还凑不齐就再按平台轮流请求一轮，直到凑满 20 首或所有平台都试过（最多两轮，避免无限请求）。
  async function loadDailyBalanced(force) {
    if (force) {
      dailyState.songs = [];
      dailyState.round += 1;
      setDailySeen(Object.create(null));
    }
    dailyState.failedSources = [];
    var sources = DAILY_SOURCES.slice();
    if (!sources.length) return 0;
    var target = DAILY_BALANCE_TOTAL;
    var perTarget = Math.max(1, Math.floor(target / sources.length));
    var requestSize = Math.max(DAILY_MIN_PER_SOURCE, perTarget);
    var added = 0;
    var round = 0;
    var leftovers = Object.create(null);
    while (added < target && round < 2) {
      round += 1;
      var refreshNetease = !!force || dailyState.round === 0 || round > 1;
      // 各平台并发请求：串行的话一个平台超时就能把一次「换一批」拖到十几秒
      var batches = await Promise.all(sources.map(function (source) {
        return fetchSourceSongs(source, requestSize, refreshNetease).then(function (songs) {
          return { source: source, songs: Array.isArray(songs) ? songs : [] };
        }).catch(function (error) {
          console.warn('[DailyRecommend:' + source + ']', error);
          return { source: source, songs: [], failed: true };
        });
      }));
      batches.forEach(function (batch) {
        if (batch.failed || !batch.songs.length) {
          if (dailyState.failedSources.indexOf(batch.source) < 0) dailyState.failedSources.push(batch.source);
        }
        var got = 0;
        var i = 0;
        for (; i < batch.songs.length; i += 1) {
          if (got >= perTarget) break;
          if (takeDailyCandidate(batch.source, batch.songs[i])) got += 1;
        }
        added += got;
        // 配额满了以后剩下的候选：留着给「补位」用（失败/歌少的平台靠它补齐）
        if (!leftovers[batch.source]) leftovers[batch.source] = [];
        for (; i < batch.songs.length; i += 1) leftovers[batch.source].push(batch.songs[i]);
      });
      // 补位：按平台轮流，一个平台一轮只补一首，保证补出来的仍然是平均分配
      var progressed = true;
      while (added < target && progressed) {
        progressed = false;
        for (var b = 0; b < batches.length && added < target; b += 1) {
          var pickSource = batches[b].source;
          var pool = leftovers[pickSource] || [];
          while (pool.length) {
            var candidate = pool.shift();
            if (takeDailyCandidate(pickSource, candidate)) { added += 1; progressed = true; break; }
          }
        }
      }
    }
    // 全是被去重拦下的「老歌」：开新一轮去重，下次下滑/换一批才能继续看到内容
    if (!added) {
      dailyState.round += 1;
      setDailySeen(Object.create(null));
    }
    return added;
  }

  // 单平台加载：换一批只替换这个平台自己的歌（别的平台缓存保留），下滑继续加载就在这个平台接着拉
  async function loadDailySourceSongs(source, force) {
    dailyState.failedSources = [];
    if (force) {
      dropDailySource(source);
      dailyState.round += 1;
    }
    var added = 0;
    var songs = [];
    try {
      songs = await fetchSourceSongs(source, DAILY_PAGE_SIZE, force || dailyState.round === 0);
    } catch (error) {
      console.warn('[DailyRecommend:' + source + ']', error);
      songs = [];
    }
    for (var i = 0; i < songs.length; i += 1) {
      if (takeDailyCandidate(source, songs[i])) added += 1;
    }
    if (!songs.length) dailyState.failedSources.push(source);
    if (!added) {
      // 一首都没收下（多半是这批全看过了）：开新一轮去重，下次下滑/换一批才有新内容
      dailyState.round += 1;
      setDailySeen(Object.create(null));
    }
    return added;
  }

  // 统一入口：「总平台」走均衡补齐，单平台只拉那一个平台；换一批（force）与下滑加载共用
  async function loadDailyNextBatch(force) {
    if (dailyState.busy) return;
    dailyState.busy = true;
    dailyState.loading = true;
    renderDailyPanel();
    try {
      if (dailyState.sourceFilter === 'all') {
        await loadDailyBalanced(force);
      } else {
        await loadDailySourceSongs(dailyState.sourceFilter, force);
      }
    } catch (error) {
      console.warn('[DailyRecommend] load failed:', error);
    } finally {
      dailyState.busy = false;
      dailyState.loading = false;
      renderDailyPanel();
      updateDailyStatusLine();
    }
  }

  // 切换平台 chip：只改「显示哪部分」，已加载的歌一律保留缓存 → 切回来不用重新请求
  function switchDailySource(source) {
    source = source || 'all';
    if (dailyState.sourceFilter === source) {
      renderDailyPanel();
      return;
    }
    dailyState.sourceFilter = source;
    dailyState.failedSources = [];
    renderDailyPanel();
    updateDailyStatusLine();
    if (!dailyVisibleSongs().length && !dailyState.busy) {
      // 这个平台还没拉过（或确实没歌）：自动补一批它的
      loadDailyNextBatch(false);
    }
  }

  var dailyEventsBound = false;
  function bindDailyEvents(mask) {
    if (dailyEventsBound) return;
    dailyEventsBound = true;
    var list = mask.querySelector('.discovery-panel-list');
    mask.querySelector('.wallpaper-engine-close').addEventListener('click', function () {
      closePanel(DAILY_PANEL_ID);
    });
    // 平台 chip：点一下只切筛选，不清已有缓存（切回来不用重新请求）
    var tabsBox = mask.querySelector('.discovery-panel-tabs');
    if (tabsBox) tabsBox.addEventListener('click', function (event) {
      var chip = event.target.closest('[data-daily-source]');
      if (!chip) return;
      switchDailySource(chip.getAttribute('data-daily-source') || 'all');
    });
    list.addEventListener('click', function (event) {
      var row = event.target.closest('[data-daily-index]');
      if (!row) return;
      playDailyAt(Number(row.getAttribute('data-daily-index')) || 0);
    });
    list.addEventListener('keydown', function (event) {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      var row = event.target.closest('[data-daily-index]');
      if (!row) return;
      event.preventDefault();
      playDailyAt(Number(row.getAttribute('data-daily-index')) || 0);
    });
    // 往下滑到底 → 加载下一批（用户要求「往下滑动就刷新」）
    list.addEventListener('scroll', function () {
      if (!dailyState.open) return;
      if (list.scrollTop + list.clientHeight < list.scrollHeight - 120) return;
      loadDailyNextBatch(false);
    }, { passive: true });
    var refreshBtn = mask.querySelector('[data-daily-refresh]');
    if (refreshBtn) refreshBtn.addEventListener('click', function () { loadDailyNextBatch(true); });
    var playAllBtn = mask.querySelector('[data-daily-playall]');
    if (playAllBtn) playAllBtn.addEventListener('click', function () {
      // 队列只放当前可见的歌（选平台时不要混进别的平台）
      if (!dailyVisibleSongs().length) { dailyStatus('还没有可播放的推荐', true); return; }
      playDailyAt(0);
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && dailyState.open) closePanel(DAILY_PANEL_ID);
    });
  }

  function openDailyRecommendPanel() {
    injectStyle();
    var mask = document.getElementById(DAILY_PANEL_ID) || buildPanelShell(DAILY_PANEL_ID, 'DAILY MIX', '每日推荐', '每日推荐');
    dailyState.open = true;
    mask.classList.add('show');
    bindDailyEvents(mask);
    updateDailyTabs(mask);
    renderDailyPanel();
    updateDailyStatusLine();
    // 当前选中范围里没有可显示的歌时才自动拉：总平台 → 均衡 20 首；单平台 → 只拉这个平台
    if (!dailyVisibleSongs().length && !dailyState.busy) {
      var firstLoad = !dailyState.songs.length;
      dailyStatus('正在读取' + (dailyState.sourceFilter === 'all' ? '各平台' : sourceLabel(dailyState.sourceFilter)) + '每日推荐…');
      loadDailyNextBatch(firstLoad);
    }
    return true;
  }

  // ---------- 对外入口（覆盖原来的行为） ----------
  root.openDailyRecommendPanel = openDailyRecommendPanel;
  root.openListenHistoryPanel = openListenHistoryPanel;
  root.closeDailyRecommendPanel = function () { closePanel(DAILY_PANEL_ID); };
  root.closeListenHistoryPanel = function () { closePanel(HISTORY_PANEL_ID); };
  root.__mrDiscoveryPanels = {
    openDaily: openDailyRecommendPanel,
    openHistory: openListenHistoryPanel,
    state: function () {
      return {
        daily: {
          songs: dailyState.songs.length,
          visible: dailyVisibleSongs().length,
          filter: dailyState.sourceFilter,
          round: dailyState.round,
          loading: dailyState.loading
        },
        history: { limit: historyState.limit, records: listenHistoryList().length }
      };
    }
  };
}(typeof window !== 'undefined' ? window : globalThis));
