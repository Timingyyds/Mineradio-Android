'use strict';
// ============================================================
// audio-metadata.js — 本地音频内嵌元数据提取（参照 folia-rc 本地库思路）
// 纯 WebView 内实现：ID3v2(mp3) / FLAC / MP4(m4a) 的封面、歌词、标题、艺术家
// 结果: { title, artist, album, coverBlobUrl, lyricText, hasEmbeddedLyrics, hasEmbeddedCover }
// ============================================================
(function () {
  'use strict';

  var HEAD_BYTES = 3 * 1024 * 1024;   // 头部读取量（ID3v2/FLAC 都在头部）
  var MP4_TAIL_BYTES = 4 * 1024 * 1024; // MP4 moov 可能位于尾部

  function decodeUtf8(bytes) {
    try { return new TextDecoder('utf-8').decode(bytes); } catch (e) { return ''; }
  }
  function decodeUtf16(bytes) {
    try {
      if (bytes.length >= 2) {
        var bom = (bytes[0] << 8) | bytes[1];
        if (bom === 0xfffe) return new TextDecoder('utf-16le').decode(bytes.subarray(2));
        if (bom === 0xfeff) return new TextDecoder('utf-16be').decode(bytes.subarray(2));
      }
      return new TextDecoder('utf-16be').decode(bytes);
    } catch (e) { return ''; }
  }
  function decodeTextWithEncoding(bytes, encoding) {
    if (!bytes || !bytes.length) return '';
    if (encoding === 1) return decodeUtf16(bytes);
    if (encoding === 2) {
      try { return new TextDecoder('utf-16be').decode(bytes); } catch (e) { return ''; }
    }
    if (encoding === 3) return decodeUtf8(bytes);
    // latin1
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return out;
  }
  function stripNull(text) {
    return String(text || '').replace(/\u0000+/g, '').trim();
  }
  function u32be(b, o) { return ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0; }
  function u32le(b, o) { return ((b[o + 3] << 24) | (b[o + 2] << 16) | (b[o + 1] << 8) | b[o]) >>> 0; }
  function syncsafe(b, o) { return ((b[o] & 0x7f) << 21) | ((b[o + 1] & 0x7f) << 14) | ((b[o + 2] & 0x7f) << 7) | (b[o + 3] & 0x7f); }
  function latinId(b, o, len) {
    var s = '';
    for (var i = 0; i < len; i++) s += String.fromCharCode(b[o + i]);
    return s;
  }

  // ---- ID3v2.3 / 2.4 ----
  function parseId3v2(buf) {
    var result = {};
    if (latinId(buf, 0, 3) !== 'ID3') return result;
    var major = buf[3];
    var flags = buf[5];
    var tagSize = syncsafe(buf, 6);
    var end = Math.min(10 + tagSize, buf.length);
    var offset = 10;
    if (flags & 0x40) { // extended header
      var extSize = major === 4 ? syncsafe(buf, offset) : u32be(buf, offset);
      offset += extSize + (major === 4 ? 0 : 4);
    }
    var frameIdLen = major === 2 ? 3 : 4;
    var headerLen = major === 2 ? 6 : 10;
    while (offset + headerLen <= end) {
      if (buf[offset] === 0) break; // padding
      var id = latinId(buf, offset, frameIdLen);
      var size;
      if (major === 2) size = (buf[offset + 3] << 16) | (buf[offset + 4] << 8) | buf[offset + 5];
      else if (major === 4) size = syncsafe(buf, offset + 4);
      else size = u32be(buf, offset + 4);
      if (size <= 0 || offset + headerLen + size > end) break;
      var body = buf.subarray(offset + headerLen, offset + headerLen + size);
      try {
        if (id === 'TIT2' || id === 'TT2') result.title = stripNull(decodeTextWithEncoding(body.subarray(1), body[0]));
        else if (id === 'TPE1' || id === 'TP1') result.artist = stripNull(decodeTextWithEncoding(body.subarray(1), body[0]));
        else if (id === 'TALB' || id === 'TAL') result.album = stripNull(decodeTextWithEncoding(body.subarray(1), body[0]));
        else if (id === 'USLT' || id === 'ULT') {
          var enc = body[0];
          var p = 1 + 3; // skip language
          // descriptor: null-terminated per encoding
          if (enc === 1 || enc === 2) {
            while (p + 1 < body.length && (body[p] !== 0 || body[p + 1] !== 0)) p += 2;
            p += 2;
          } else {
            while (p < body.length && body[p] !== 0) p += 1;
            p += 1;
          }
          var lyricText = stripNull(decodeTextWithEncoding(body.subarray(p), enc));
          if (lyricText) result.lyricText = lyricText;
        } else if (id === 'APIC' || id === 'PIC') {
          var enc2 = body[0];
          var q = 1;
          var mime = 'image/jpeg';
          if (id === 'APIC') {
            while (q < body.length && body[q] !== 0) q += 1;
            mime = stripNull(latinId(buf, 0, 0) + decodeTextWithEncoding(body.subarray(1, q), 3)) || 'image/jpeg';
            q += 1;
          } else {
            q += 3; // 3-char image format
          }
          q += 1; // picture type
          if (enc2 === 1 || enc2 === 2) {
            while (q + 1 < body.length && (body[q] !== 0 || body[q + 1] !== 0)) q += 2;
            q += 2;
          } else {
            while (q < body.length && body[q] !== 0) q += 1;
            q += 1;
          }
          var imgBytes = body.subarray(q);
          if (imgBytes.length > 128 && !result.coverBlobUrl) {
            result.coverBlobUrl = URL.createObjectURL(new Blob([imgBytes], { type: /^image\/png/.test(mime) ? 'image/png' : 'image/jpeg' }));
          }
        }
      } catch (e) {}
      offset += headerLen + size;
    }
    return result;
  }

  // ---- FLAC ----
  function parseFlac(buf) {
    var result = {};
    if (latinId(buf, 0, 4) !== 'fLaC') return result;
    var offset = 4;
    while (offset + 4 <= buf.length) {
      var header = buf[offset];
      var isLast = (header & 0x80) !== 0;
      var type = header & 0x7f;
      var size = (buf[offset + 1] << 16) | (buf[offset + 2] << 8) | buf[offset + 3];
      var bodyStart = offset + 4;
      if (bodyStart + size > buf.length) break;
      var body = buf.subarray(bodyStart, bodyStart + size);
      try {
        if (type === 4) { // VORBIS_COMMENT
          var p = 0;
          var vendorLen = u32le(body, p); p += 4 + vendorLen;
          var count = u32le(body, p); p += 4;
          for (var i = 0; i < count && p + 4 <= body.length; i++) {
            var len = u32le(body, p); p += 4;
            var entry = decodeUtf8(body.subarray(p, p + len)); p += len;
            var eq = entry.indexOf('=');
            if (eq <= 0) continue;
            var key = entry.slice(0, eq).toUpperCase();
            var value = entry.slice(eq + 1).trim();
            if (!value) continue;
            if (key === 'TITLE' && !result.title) result.title = value;
            else if (key === 'ARTIST' && !result.artist) result.artist = value;
            else if (key === 'ALBUM' && !result.album) result.album = value;
            else if ((key === 'LYRICS' || key === 'UNSYNCEDLYRICS' || key === 'UNSYNCED LYRICS') && !result.lyricText) result.lyricText = value;
          }
        } else if (type === 6 && !result.coverBlobUrl) { // PICTURE
          var q = 0;
          q += 4; // picture type
          var mimeLen = u32be(body, q); q += 4;
          var mime = decodeUtf8(body.subarray(q, q + mimeLen)) || 'image/jpeg'; q += mimeLen;
          var descLen = u32be(body, q); q += 4 + descLen;
          q += 16; // w/h/depth/colors
          var dataLen = u32be(body, q); q += 4;
          if (dataLen > 128 && q + dataLen <= body.length) {
            result.coverBlobUrl = URL.createObjectURL(new Blob([body.subarray(q, q + dataLen)], { type: /png/i.test(mime) ? 'image/png' : 'image/jpeg' }));
          }
        }
      } catch (e) {}
      offset = bodyStart + size;
      if (isLast) break;
    }
    return result;
  }

  // ---- MP4 / M4A ----
  function parseMp4Boxes(buf, start, end, out) {
    var offset = start;
    while (offset + 8 <= end) {
      var size = u32be(buf, offset);
      var type = latinId(buf, offset + 4, 4);
      if (size < 8 || offset + size > end) break;
      try {
        if (type === 'moov' || type === 'udta' || type === 'ilst' || type === 'trak' || type === 'mdia') {
          var innerStart = offset + 8;
          if (type === 'meta') innerStart = offset + 12; // meta 有 4 字节 version/flags
          parseMp4Boxes(buf, innerStart, offset + size, out);
        } else if (type === '\u00a9nam' && !out.title) out.title = stripNull(decodeUtf8(mp4DataPayload(buf, offset, size)));
        else if (type === '\u00a9ART' && !out.artist) out.artist = stripNull(decodeUtf8(mp4DataPayload(buf, offset, size)));
        else if (type === '\u00a9alb' && !out.album) out.album = stripNull(decodeUtf8(mp4DataPayload(buf, offset, size)));
        else if (type === '\u00a9lyr' && !out.lyricText) out.lyricText = stripNull(decodeUtf8(mp4DataPayload(buf, offset, size)));
        else if (type === 'covr' && !out.coverBlobUrl) {
          var img = mp4DataPayload(buf, offset, size);
          if (img && img.length > 128) {
            var isPng = img[0] === 0x89 && img[1] === 0x50;
            out.coverBlobUrl = URL.createObjectURL(new Blob([img], { type: isPng ? 'image/png' : 'image/jpeg' }));
          }
        }
      } catch (e) {}
      offset += size;
    }
  }
  function mp4DataPayload(buf, boxOffset, boxSize) {
    // ilst 子 box: [size][type]['data'][ver/flags 4][locale 4][payload]
    var p = boxOffset + 8;
    if (latinId(buf, p, 4) !== 'data') return null;
    var dataSize = u32be(buf, p + 4);
    var payloadStart = p + 16;
    var payloadEnd = Math.min(boxOffset + boxSize, payloadStart + dataSize - 8);
    if (payloadEnd <= payloadStart) return null;
    return buf.subarray(payloadStart, payloadEnd);
  }
  function parseMp4(buf, tailBuf) {
    var result = {};
    parseMp4Boxes(buf, 0, buf.length, result);
    if (!result.title && !result.coverBlobUrl && tailBuf) parseMp4Boxes(tailBuf, 0, tailBuf.length, result);
    return result;
  }

  // ---- OGG / OPUS（Vorbis Comment，folia 同款格式覆盖）----
  function parseVorbisComment(body, out) {
    var p = 0;
    var vendorLen = u32le(body, p); p += 4 + vendorLen;
    if (p + 4 > body.length) return;
    var count = u32le(body, p); p += 4;
    for (var i = 0; i < count && p + 4 <= body.length; i++) {
      var len = u32le(body, p); p += 4;
      if (p + len > body.length) break;
      var entry = decodeUtf8(body.subarray(p, p + len)); p += len;
      var eq = entry.indexOf('=');
      if (eq <= 0) continue;
      var key = entry.slice(0, eq).toUpperCase();
      var value = entry.slice(eq + 1).trim();
      if (!value) continue;
      if (key === 'TITLE' && !out.title) out.title = value;
      else if (key === 'ARTIST' && !out.artist) out.artist = value;
      else if (key === 'ALBUM' && !out.album) out.album = value;
      else if ((key === 'LYRICS' || key === 'UNSYNCEDLYRICS' || key === 'UNSYNCED LYRICS') && !out.lyricText) out.lyricText = value;
    }
  }
  function parseOgg(buf) {
    var result = {};
    // Ogg page: 'OggS' + 26 字节头 + segTable + payload；Vorbis Comment 在第二个包
    var offset = 0;
    var commentBody = null;
    while (offset + 27 <= buf.length) {
      if (latinId(buf, offset, 4) !== 'OggS') break;
      var segCount = buf[offset + 26];
      var payloadLen = 0;
      for (var s = 0; s < segCount; s++) payloadLen += buf[offset + 27 + s];
      var payloadStart = offset + 27 + segCount;
      var payloadEnd = Math.min(payloadStart + payloadLen, buf.length);
      if (payloadEnd > payloadStart && !commentBody) {
        var head8 = latinId(buf, payloadStart + 1, 6);
        // \x03vorbis (Vorbis) / OpusTags (Opus)
        if (buf[payloadStart] === 0x03 && head8.slice(0, 6) === 'vorbis') {
          commentBody = buf.subarray(payloadStart + 7, payloadEnd);
        } else if (latinId(buf, payloadStart, 8) === 'OpusTags') {
          commentBody = buf.subarray(payloadStart + 8, payloadEnd);
        }
      }
      offset = payloadEnd;
      if (commentBody && offset > HEAD_BYTES) break;
    }
    if (commentBody) parseVorbisComment(commentBody, result);
    return result;
  }

  window.extractAudioMetadata = async function (file) {
    var result = { title: '', artist: '', album: '', coverBlobUrl: '', lyricText: '', hasEmbeddedLyrics: false, hasEmbeddedCover: false };
    if (!file) return result;
    var headSlice = file.slice(0, Math.min(HEAD_BYTES, file.size));
    var head = new Uint8Array(await headSlice.arrayBuffer());
    var parsed = {};
    if (head.length > 10 && latinId(head, 0, 3) === 'ID3') parsed = parseId3v2(head);
    else if (head.length > 8 && latinId(head, 0, 4) === 'fLaC') parsed = parseFlac(head);
    else if (head.length > 12 && latinId(head, 4, 4) === 'ftyp') {
      parsed = parseMp4(head, null);
      if (!parsed.title && !parsed.coverBlobUrl && file.size > head.length) {
        var tailSlice = file.slice(Math.max(0, file.size - MP4_TAIL_BYTES), file.size);
        var tail = new Uint8Array(await tailSlice.arrayBuffer());
        parsed = parseMp4(tail, null);
      }
    } else if (head.length > 4 && latinId(head, 0, 4) === 'OggS') {
      parsed = parseOgg(head);
    }
    result.title = parsed.title || '';
    result.artist = parsed.artist || '';
    result.album = parsed.album || '';
    result.lyricText = parsed.lyricText || '';
    result.coverBlobUrl = parsed.coverBlobUrl || '';
    result.hasEmbeddedLyrics = !!result.lyricText;
    result.hasEmbeddedCover = !!result.coverBlobUrl;
    return result;
  };
})();
