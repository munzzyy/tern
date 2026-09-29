'use strict';

var TERN_PACKAGE = 'io.github.munzzyy.tern';
var HOME = 'https://munzzyy.github.io/tern/';
var BADGE = HOME + 'badge.png';

/** The app address in a page's query, when it is one Tern will take: https, no login, no spaces. */
function appAddress(search) {
  var raw = new URLSearchParams(search).get('url');
  if (!raw || raw.length > 2000 || /[\s\u0000-\u001f\u007f]/.test(raw)) return null;
  var url;
  try {
    url = new URL(raw);
  } catch (e) {
    return null;
  }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) return null;
  return url;
}

/** What the add page offers for [url]: the words to show and the two ways to hand it to Tern. */
function addTarget(url) {
  var address = url.href;
  var path = url.pathname === '/' ? '' : url.pathname.replace(/\/+$/, '');
  return {
    address: address,
    shown: url.host + path,
    intent: 'intent://add?url=' + encodeURIComponent(address) +
      '#Intent;scheme=tern;package=' + TERN_PACKAGE +
      ';S.browser_fallback_url=' + encodeURIComponent(HOME + '?from=add') + ';end',
    link: 'tern://add?url=' + encodeURIComponent(address)
  };
}

/** The Markdown for a README: the badge, linked to the add page for [url]. */
function badgeMarkdown(url) {
  return '[<img src="' + BADGE + '" alt="Get it with Tern" height="80">](' + HOME + 'add/?url=' + encodeURIComponent(url.href) + ')';
}

if (typeof document !== 'undefined') {
  var page = document.body.getAttribute('data-page');
  var show = function (id, on) { document.getElementById(id).hidden = !on; };

  if (page === 'add') {
    var url = appAddress(location.search);
    show('good', !!url);
    show('bad', !url);
    if (url) {
      var target = addTarget(url);
      var android = /Android/i.test(navigator.userAgent);
      document.getElementById('address').textContent = target.shown;
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

if (typeof module !== 'undefined') module.exports = { appAddress: appAddress, addTarget: addTarget, badgeMarkdown: badgeMarkdown };
