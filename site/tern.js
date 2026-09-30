'use strict';

var TERN_PACKAGE = 'io.github.munzzyy.tern';
var HOME = 'https://tern.munzzyy.dev/';
var BADGE = HOME + 'badge.png';

/**
 * What the page was given, read from after '#', which a browser never sends to any server, and
 * from after '?' in the links made before Tern used '#'.
 */
function given(search, hash) {
  var fromHash = new URLSearchParams(hash && hash.charAt(0) === '#' ? hash.slice(1) : '');
  var fromQuery = new URLSearchParams(search || '');
  return {
    url: fromHash.get('url') || fromQuery.get('url'),
    app: fromHash.get('app')
  };
}

/** [raw] as an app address Tern will take: https, no login, no spaces. */
function httpsAddress(raw) {
  if (typeof raw !== 'string' || !raw || raw.length > 2000 || /[\s\u0000-\u001f\u007f]/.test(raw)) return null;
  var url;
  try {
    url = new URL(raw);
  } catch (e) {
    return null;
  }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) return null;
  return url;
}

/** The app address the page was given, when it is one Tern will take. */
function appAddress(search, hash) {
  return httpsAddress(given(search, hash).url);
}

/**
 * The settings of an app the page was given after '#app=', as Tern and Obtainium write them: the
 * JSON, the app's address and its name. Null when there are none, or they are not an app.
 */
function appSettings(hash) {
  var raw = given('', hash).app;
  if (!raw || raw.length > 8000) return null;
  var app;
  try {
    app = JSON.parse(raw);
  } catch (e) {
    return null;
  }
  if (!app || typeof app !== 'object' || Array.isArray(app)) return null;
  var url = httpsAddress(app.url);
  if (!url) return null;
  var name = typeof app.name === 'string' ? app.name.replace(/[\u0000-\u001f\u007f\u200e\u200f\u202a-\u202e\u2066-\u2069]/g, '').slice(0, 200) : '';
  return { json: raw, url: url, name: name };
}

/** How the page hands [path] to Tern: tern:// anywhere, and on Android an intent that falls back to the home page. */
function handOff(path) {
  return {
    intent: 'intent://' + path + '#Intent;scheme=tern;package=' + TERN_PACKAGE +
      ';S.browser_fallback_url=' + encodeURIComponent(HOME + '?from=add') + ';end',
    link: 'tern://' + path
  };
}

function shownAddress(url) {
  var path = url.pathname === '/' ? '' : url.pathname.replace(/\/+$/, '');
  return url.host + path;
}

/** What the add page offers for [url]: the words to show and the two ways to hand it to Tern. */
function addTarget(url) {
  var address = url.href;
  var hand = handOff('add?url=' + encodeURIComponent(address));
  return { address: address, shown: shownAddress(url), intent: hand.intent, link: hand.link };
}

/** The same for an app that comes with its settings: Tern shows them before anything is added. */
function settingsTarget(settings) {
  var hand = handOff('app/' + encodeURIComponent(settings.json));
  return { address: settings.url.href, shown: shownAddress(settings.url), name: settings.name, intent: hand.intent, link: hand.link };
}

/** The Markdown for a README: the badge, linked to the add page for [url]. */
function badgeMarkdown(url) {
  return '[<img src="' + BADGE + '" alt="Get it with Tern" height="80">](' + HOME + 'add/#url=' + encodeURIComponent(url.href) + ')';
}

if (typeof document !== 'undefined') {
  var page = document.body.getAttribute('data-page');
  var show = function (id, on) { document.getElementById(id).hidden = !on; };

  if (page === 'add') {
    var settings = appSettings(location.hash);
    var url = settings ? null : appAddress(location.search, location.hash);
    var target = settings ? settingsTarget(settings) : url ? addTarget(url) : null;
    show('good', !!target);
    show('bad', !target);
    show('with-settings', !!settings);
    if (target) {
      var android = /Android/i.test(navigator.userAgent);
      document.getElementById('address').textContent = target.name ? target.name + ', ' + target.shown : target.shown;
      document.getElementById('open').href = android ? target.intent : target.link;
      show('elsewhere', !android);
      var copy = document.getElementById('copy');
      copy.addEventListener('click', function () {
        navigator.clipboard.writeText(target.address).then(function () {
          copy.textContent = 'Copied';
        }, function () {
          copy.textContent = 'Could not copy';
        });
      });
    }
  }

  if (page === 'home') {
    var field = document.getElementById('repo');
    var out = document.getElementById('snippet');
    var said = document.getElementById('snippet-said');
    var update = function () {
      var entered = field.value.trim();
      var url = entered ? appAddress('?url=' + encodeURIComponent(/^https:\/\//i.test(entered) ? entered : 'https://' + entered)) : null;
      out.value = url ? badgeMarkdown(url) : '';
      said.textContent = entered && !url ? 'That is not an https address.' : '';
    };
    field.addEventListener('input', update);
    if (new URLSearchParams(location.search).get('from') === 'add') show('from-add', true);
  }
}

if (typeof module !== 'undefined') module.exports = { appAddress: appAddress, appSettings: appSettings, addTarget: addTarget, settingsTarget: settingsTarget, badgeMarkdown: badgeMarkdown };
