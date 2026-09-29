// Checks the site's script: which addresses the add page takes, and the links it makes of them.
//   node tools/check-site.js
const assert = require('assert');
const t = require(require('path').join(__dirname, '..', 'site', 'tern.js'));
let n = 0;
const ok = (c, m) => { assert.ok(c, m); n++; };
const eq = (a, b, m) => { assert.strictEqual(a, b, m); n++; };

const good = t.appAddress('?url=' + encodeURIComponent('https://github.com/munzzyy/starling'));
ok(good, 'a plain GitHub address is taken');
const target = t.addTarget(good);
eq(target.shown, 'github.com/munzzyy/starling', 'the page shows host and path');
eq(target.link, 'tern://add?url=https%3A%2F%2Fgithub.com%2Fmunzzyy%2Fstarling', 'the tern link carries the address encoded');
eq(target.intent, 'intent://add?url=https%3A%2F%2Fgithub.com%2Fmunzzyy%2Fstarling#Intent;scheme=tern;package=io.github.munzzyy.tern;S.browser_fallback_url=https%3A%2F%2Fmunzzyy.github.io%2Ftern%2F%3Ffrom%3Dadd;end', 'the intent names the package and falls back to the home page');

for (const bad of ['http://github.com/a/b', 'javascript:alert(1)', 'https://user:pw@github.com/a/b', 'https://github.com/a b', 'https://github.com/a\nb', 'data:text/html,x', '', 'https://' + 'a'.repeat(2000) + '.org', 'file:///etc/passwd', 'intent://x#Intent;end']) {
  eq(t.appAddress('?url=' + encodeURIComponent(bad)), null, 'refused: ' + JSON.stringify(bad.slice(0, 40)));
}
eq(t.appAddress(''), null, 'no query is refused');
eq(t.appAddress('?other=https://github.com/a/b'), null, 'another parameter is not taken for the address');

const tricky = t.addTarget(t.appAddress('?url=' + encodeURIComponent('https://example.org/app?x=1&y=2#top')));
ok(!tricky.intent.includes('&y=2#top;'), 'the query and fragment of the address stay inside the encoded value');
ok(tricky.intent.split('#Intent;').length === 2, 'the address cannot add a second intent part');

const angle = t.addTarget(t.appAddress('?url=' + encodeURIComponent('https://example.org/"><script>x</script>')));
ok(!angle.intent.includes('<') && !angle.intent.includes('"'), 'markup in the address is encoded in the intent');

eq(t.badgeMarkdown(good), '[<img src="https://munzzyy.github.io/tern/badge.png" alt="Get it with Tern" height="80">](https://munzzyy.github.io/tern/add/?url=https%3A%2F%2Fgithub.com%2Fmunzzyy%2Fstarling)', 'the badge Markdown links the add page with the address encoded');
console.log(`ok   ${n} checks of the site's script`);
