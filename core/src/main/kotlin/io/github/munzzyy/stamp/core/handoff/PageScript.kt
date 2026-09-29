package io.github.munzzyy.stamp.core.handoff

/**
 * The script of the page, served byte for byte as it stands here. It seals what is sent with the
 * code and posts it. SHA-256, HMAC and ChaCha20 are written out in it because a page that comes
 * over plain HTTP gets no crypto.subtle from its browser.
 */
internal object PageScript {
    const val TEXT = """
'use strict';

var ALPHABET = 'abcdefghijklmnopqrstuvwxyz234567';
var CODE_LENGTH = 20;
var BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
var LINKS = 1;
var FILE = 2;
var K = [
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];

function rotr(x, n) {
  return (x >>> n) | (x << (32 - n));
}

function rotl(x, n) {
  return (x << n) | (x >>> (32 - n));
}

function sha256(message) {
  var length = message.length;
  var padded = new Uint8Array(((length + 9 + 63) >>> 6) << 6);
  padded.set(message);
  padded[length] = 0x80;
  var bits = [length >>> 29, length << 3];
  var i;
  for (i = 0; i < 8; i++) padded[padded.length - 8 + i] = (bits[i >> 2] >>> (24 - 8 * (i & 3))) & 0xff;

  var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var w = new Int32Array(64);
  for (var at = 0; at < padded.length; at += 64) {
    for (i = 0; i < 16; i++) {
      w[i] = (padded[at + 4 * i] << 24) | (padded[at + 4 * i + 1] << 16) | (padded[at + 4 * i + 2] << 8) | padded[at + 4 * i + 3];
    }
    for (i = 16; i < 64; i++) {
      var s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      var s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }
    var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
    for (i = 0; i < 64; i++) {
      var t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
      var t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) | 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) | 0;
    }
    h[0] = (h[0] + a) | 0;
    h[1] = (h[1] + b) | 0;
    h[2] = (h[2] + c) | 0;
    h[3] = (h[3] + d) | 0;
    h[4] = (h[4] + e) | 0;
    h[5] = (h[5] + f) | 0;
    h[6] = (h[6] + g) | 0;
    h[7] = (h[7] + hh) | 0;
  }
  var out = new Uint8Array(32);
  for (i = 0; i < 32; i++) out[i] = (h[i >> 2] >>> (24 - 8 * (i & 3))) & 0xff;
  return out;
}

function hmac(key, message) {
  if (key.length > 64) key = sha256(key);
  var inner = new Uint8Array(64 + message.length);
  var outer = new Uint8Array(64 + 32);
  for (var i = 0; i < 64; i++) {
    var k = i < key.length ? key[i] : 0;
    inner[i] = k ^ 0x36;
    outer[i] = k ^ 0x5c;
  }
  inner.set(message, 64);
  outer.set(sha256(inner), 64);
  return sha256(outer);
}

function quarter(s, a, b, c, d) {
  s[a] = (s[a] + s[b]) | 0;
  s[d] = rotl(s[d] ^ s[a], 16);
  s[c] = (s[c] + s[d]) | 0;
  s[b] = rotl(s[b] ^ s[c], 12);
  s[a] = (s[a] + s[b]) | 0;
  s[d] = rotl(s[d] ^ s[a], 8);
  s[c] = (s[c] + s[d]) | 0;
  s[b] = rotl(s[b] ^ s[c], 7);
}

function word(bytes, at) {
  return bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16) | (bytes[at + 3] << 24);
}

function chacha20(key, nonce, firstBlock, data) {
  var start = new Int32Array(16);
  start[0] = 0x61707865;
  start[1] = 0x3320646e;
  start[2] = 0x79622d32;
  start[3] = 0x6b206574;
  var i;
  for (i = 0; i < 8; i++) start[4 + i] = word(key, 4 * i);
  start[12] = firstBlock;
  for (i = 0; i < 3; i++) start[13 + i] = word(nonce, 4 * i);

  var out = new Uint8Array(data.length);
  var s = new Int32Array(16);
  for (var at = 0; at < data.length; at += 64) {
    s.set(start);
    for (var round = 0; round < 10; round++) {
      quarter(s, 0, 4, 8, 12);
      quarter(s, 1, 5, 9, 13);
      quarter(s, 2, 6, 10, 14);
      quarter(s, 3, 7, 11, 15);
      quarter(s, 0, 5, 10, 15);
      quarter(s, 1, 6, 11, 12);
      quarter(s, 2, 7, 8, 13);
      quarter(s, 3, 4, 9, 14);
    }
    for (i = 0; i < 16; i++) s[i] = (s[i] + start[i]) | 0;
    for (i = 0; i < 64 && at + i < data.length; i++) {
      out[at + i] = data[at + i] ^ ((s[i >> 2] >>> (8 * (i & 3))) & 0xff);
    }
    start[12] = (start[12] + 1) | 0;
  }
  return out;
}

function utf8(text) {
  var out = [];
  for (var i = 0; i < text.length; i++) {
    var c = text.charCodeAt(i);
    if (c >= 0xd800 && c < 0xdc00 && i + 1 < text.length) {
      var low = text.charCodeAt(i + 1);
      if (low >= 0xdc00 && low < 0xe000) {
        c = 0x10000 + ((c - 0xd800) << 10) + (low - 0xdc00);
        i++;
      }
    }
    if (c >= 0xd800 && c < 0xe000) c = 0xfffd;
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
    } else if (c < 0x10000) {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    } else {
      out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
  }
  return new Uint8Array(out);
}

function inBase64(bytes) {
  var parts = [];
  var text = '';
  for (var i = 0; i < bytes.length; i += 3) {
    var left = bytes.length - i;
    var b0 = bytes[i];
    var b1 = left > 1 ? bytes[i + 1] : 0;
    var b2 = left > 2 ? bytes[i + 2] : 0;
    text += BASE64.charAt(b0 >> 2) + BASE64.charAt(((b0 & 3) << 4) | (b1 >> 4));
    if (left > 1) text += BASE64.charAt(((b1 & 15) << 2) | (b2 >> 6));
    if (left > 2) text += BASE64.charAt(b2 & 63);
    if (text.length >= 8192) {
      parts.push(text);
      text = '';
    }
  }
  parts.push(text);
  return parts.join('');
}

function codeFrom(typed) {
  var code = '';
  for (var i = 0; i < typed.length; i++) {
    var c = typed.charAt(i);
    if (c === ' ' || c === '-') continue;
    if (c >= 'A' && c <= 'Z') c = ALPHABET.charAt(c.charCodeAt(0) - 65);
    if (ALPHABET.indexOf(c) < 0) return null;
    code += c;
  }
  return code;
}

function named(name, content, most) {
  var bytes = utf8(name);
  var length = bytes.length;
  if (length > most) {
    length = most;
    while (length > 0 && (bytes[length] & 0xc0) === 0x80) length--;
  }
  var out = new Uint8Array(1 + length + content.length);
  out[0] = length;
  out.set(bytes.subarray(0, length), 1);
  out.set(content, 1 + length);
  return out;
}

function seal(code, kind, nonce, plain) {
  var master = hmac(utf8(code), utf8('stamp handoff v1'));
  var sealed = chacha20(hmac(master, utf8('enc')), nonce, 1, plain);
  var out = new Uint8Array(13 + sealed.length + 32);
  out[0] = kind;
  out.set(nonce, 1);
  out.set(sealed, 13);
  out.set(hmac(hmac(master, utf8('mac')), out.subarray(0, 13 + sealed.length)), 13 + sealed.length);
  return out;
}

function start() {
  var code = null;
  var sender = document.getElementById('sender');
  var codePart = document.getElementById('code-part');
  var codeField = document.getElementById('code');
  var codeSaid = document.getElementById('code-said');
  var linksForm = document.getElementById('links-form');
  var linksField = document.getElementById('links');
  var linksSaid = document.getElementById('links-said');
  var fileForm = document.getElementById('file-form');
  var fileField = document.getElementById('file');
  var fileSaid = document.getElementById('file-said');
  var buttons = document.getElementsByTagName('button');

  function say(where, text, good) {
    where.textContent = text;
    where.className = !text ? '' : good ? 'notice good' : 'notice bad';
  }

  function busy(now) {
    for (var i = 0; i < buttons.length; i++) buttons[i].disabled = now;
  }

  function codeNow() {
    if (code) return code;
    var typed = codeFrom(codeField.value);
    var problem = '';
    if (typed === null) problem = 'data-wrong-character';
    else if (typed.length !== CODE_LENGTH) problem = 'data-wrong-length';
    say(codeSaid, problem ? codePart.getAttribute(problem) : '', false);
    if (problem) codeField.focus();
    return problem ? null : typed;
  }

  function send(kind, plain, used, said, field) {
    var nonce = new Uint8Array(12);
    crypto.getRandomValues(nonce);
    var request = new XMLHttpRequest();
    request.open('POST', '/send');
    request.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
    request.responseType = 'document';
    request.onload = function () {
      var notice = request.response && request.response.getElementById('notice');
      say(said, notice ? notice.textContent : sender.getAttribute('data-no-answer'), request.status === 200);
      if (request.status === 200) {
        code = used;
        codePart.hidden = true;
        codeField.value = '';
        field.value = '';
      }
      if (request.status === 403) {
        code = null;
        codePart.hidden = false;
        codeField.focus();
      }
      busy(false);
    };
    request.onerror = function () {
      say(said, sender.getAttribute('data-no-answer'), false);
      busy(false);
    };
    request.ontimeout = request.onerror;
    request.timeout = 60000;
    busy(true);
    request.send('sealed=' + inBase64(seal(used, kind, nonce, plain)));
  }

  linksForm.addEventListener('submit', function (e) {
    e.preventDefault();
    var typed = linksField.value.split(/\r\n|\r|\n/);
    var lines = [];
    var longest = 0;
    for (var i = 0; i < typed.length; i++) {
      var line = typed[i].trim();
      if (line) lines.push(line);
      if (line.length > longest) longest = line.length;
    }
    var problem = '';
    if (!lines.length) problem = 'data-none';
    else if (lines.length > Number(linksForm.getAttribute('data-most'))) problem = 'data-too-many';
    else if (longest > Number(linksForm.getAttribute('data-longest'))) problem = 'data-too-long';
    say(linksSaid, problem ? linksForm.getAttribute(problem) : '', false);
    var used = problem ? null : codeNow();
    if (used) send(LINKS, utf8(lines.join('\n')), used, linksSaid, linksField);
  });

  fileForm.addEventListener('submit', function (e) {
    e.preventDefault();
    var file = fileField.files && fileField.files[0];
    var problem = '';
    if (!file || !file.size) problem = 'data-none';
    else if (file.size > Number(fileForm.getAttribute('data-most'))) problem = 'data-too-large';
    say(fileSaid, problem ? fileForm.getAttribute(problem) : '', false);
    var used = problem ? null : codeNow();
    if (!used) return;
    var reader = new FileReader();
    reader.onload = function () {
      send(FILE, named(file.name, new Uint8Array(reader.result), Number(fileForm.getAttribute('data-name'))), used, fileSaid, fileField);
    };
    reader.onerror = function () {
      say(fileSaid, fileForm.getAttribute('data-unread'), false);
    };
    reader.readAsArrayBuffer(file);
  });

  var given = location.hash.substring(1);
  if (given) {
    history.replaceState(null, '', location.pathname);
    given = codeFrom(given);
    if (given !== null && given.length === CODE_LENGTH) code = given;
  }
  codePart.hidden = code !== null;
  var can = typeof crypto !== 'undefined' && crypto.getRandomValues && typeof FileReader !== 'undefined';
  (can ? sender : document.getElementById('cannot')).hidden = false;
}

if (typeof document !== 'undefined') start();
"""
}
