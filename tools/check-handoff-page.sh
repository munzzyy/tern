#!/usr/bin/env bash
# Checks the page of the handoff the way it is served, not a copy of it.
# Part one, with node: the page's own SHA-256, HMAC and ChaCha20 against the vectors of the RFCs
# and against OpenSSL, its seal against core/src/test/resources/fixtures/handoff/sealed.txt,
# and the hashes in the Content-Security-Policy against the script and the style.
# Part two, with a headless Chromium: the page against a live handoff on 127.0.0.1.
# --no-browser leaves part two out and says so.
# Requires node. Part two requires node 22 or newer and chromium, chromium-browser or google-chrome.
set -euo pipefail

BROWSER=yes
[ "${1:-}" = "--no-browser" ] && BROWSER=no

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/core/build/handoff-page"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}
JAVA=java
[ -x "$JAVA_HOME/bin/java" ] && JAVA="$JAVA_HOME/bin/java"

cd "$ROOT"
./gradlew --no-daemon --console=plain -q :core:testClasses
rm -rf "$WORK"
mkdir -p "$WORK"
KOTLIN=$(sed -n 's/.*org\.jetbrains\.kotlin\.jvm") version "\([^"]*\)".*/\1/p' build.gradle.kts)
STDLIB=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/$KOTLIN" -name "kotlin-stdlib-$KOTLIN.jar" | head -1)
export TERN_CLASSES="$ROOT/core/build/classes/kotlin/main:$ROOT/core/build/classes/kotlin/test:$STDLIB"
export TERN_JAVA="$JAVA"
export TERN_WORK="$WORK"
export TERN_FIXTURE="$ROOT/core/src/test/resources/fixtures/handoff/sealed.txt"

"$JAVA" -cp "$TERN_CLASSES" io.github.munzzyy.tern.core.handoff.PageToolKt page > "$WORK/answer"

node - <<'JS'
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const vm = require('vm');

let failed = 0;
function check(what, ok, detail) {
  if (!ok) failed++;
  console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok || detail === undefined ? '' : ': ' + detail));
}
const hex = (bytes) => Buffer.from(bytes).toString('hex');
const unhex = (text) => new Uint8Array(Buffer.from(text, 'hex'));
const ascii = (text) => new Uint8Array(Buffer.from(text, 'latin1'));
const same = (what, expected, got) => check(what, expected === got, 'expected ' + expected + ' and got ' + got);

const answer = fs.readFileSync(process.env.TERN_WORK + '/answer');
const split = answer.indexOf('\r\n\r\n');
const head = answer.subarray(0, split).toString('latin1').split('\r\n');
const html = answer.subarray(split + 4).toString('utf8');
const inside = (open, close) => {
  const parts = html.split(open);
  if (parts.length !== 2) throw new Error('the page has ' + (parts.length - 1) + ' of ' + open);
  return parts[1].split(close)[0];
};
const script = inside('<script>', '</script>');
const style = inside('<style>', '</style>');
fs.writeFileSync(process.env.TERN_WORK + '/page.html', html);
fs.writeFileSync(process.env.TERN_WORK + '/page.js', script);
console.log('the page is ' + Buffer.byteLength(html) + ' bytes, ' + Buffer.byteLength(script) + ' of them its script');

const policy = head.filter((line) => line.startsWith('Content-Security-Policy: ')).map((line) => line.substring(25));
console.log(head[0]);
console.log(policy.join('\n'));
const sha = (text) => crypto.createHash('sha256').update(text, 'utf8').digest('base64');
same('one policy, which names the hash of the script and of the style that are served', JSON.stringify(policy), JSON.stringify([
  "default-src 'none'; script-src 'sha256-" + sha(script) + "'; style-src 'sha256-" + sha(style) + "'; " +
  "connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
]));
check('the script is ASCII and ends only at its end', /^[\n\x20-\x7e]*$/.test(script) && !script.includes('</') && !script.includes('<!--'));
for (const word of ['crypto.subtle', 'BigInt', 'TextEncoder', 'eval(', 'innerHTML', 'import', 'fetch(']) check('the script does without ' + word, !script.includes(word));

const page = {};
vm.runInNewContext(script, page);
check('the script starts nothing where there is no document', typeof page.seal === 'function' && typeof page.start === 'function');

const hashes = [
  ['', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
  ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
  ['abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq', '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1'],
];
for (const [text, expected] of hashes) same('SHA-256 of FIPS 180 for ' + text.length + ' bytes', expected, hex(page.sha256(ascii(text))));
same('SHA-256 of a million letters a', 'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0', hex(page.sha256(new Uint8Array(1000000).fill(97))));

const long = new Uint8Array(131).fill(0xaa);
const macs = [
  [new Uint8Array(20).fill(0x0b), ascii('Hi There'), 'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7'],
  [ascii('Jefe'), ascii('what do ya want for nothing?'), '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843'],
  [new Uint8Array(20).fill(0xaa), new Uint8Array(50).fill(0xdd), '773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe'],
  [unhex('0102030405060708090a0b0c0d0e0f10111213141516171819'), new Uint8Array(50).fill(0xcd), '82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b'],
  [long, ascii('Test Using Larger Than Block-Size Key - Hash Key First'), '60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54'],
  [long, ascii('This is a test using a larger than block-size key and a larger than block-size data. The key needs to be hashed before being used by the HMAC algorithm.'), '9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2'],
];
macs.forEach(([key, message, expected], i) => same('HMAC-SHA256 of RFC 4231, case ' + (i < 4 ? i + 1 : i + 2), expected, hex(page.hmac(key, message))));

const key = unhex('000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f');
same('ChaCha20 of RFC 8439 2.3.2, one block of key stream',
  '10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4ed2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e',
  hex(page.chacha20(key, unhex('000000090000004a00000000'), 1, new Uint8Array(64))));
same('ChaCha20 of RFC 8439 2.4.2',
  '6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8' +
  '07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d',
  hex(page.chacha20(key, unhex('000000000000004a00000000'), 1, ascii("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."))));

let agree = 0;
const lengths = [];
for (let n = 0; n <= 200; n++) lengths.push(n);
lengths.push(255, 256, 257, 1023, 1024, 1025, 65537, 2 * 1024 * 1024 + 81);
for (const length of lengths) {
  const k = crypto.randomBytes(32);
  const nonce = crypto.randomBytes(12);
  const data = crypto.randomBytes(length);
  const first = Buffer.alloc(4);
  first.writeUInt32LE(1);
  const cipher = crypto.createCipheriv('chacha20', k, Buffer.concat([first, nonce]));
  const sealed = Buffer.concat([cipher.update(data), cipher.final()]);
  const ours = page.chacha20(new Uint8Array(k), new Uint8Array(nonce), 1, new Uint8Array(data));
  const hashed = hex(page.sha256(new Uint8Array(data))) === crypto.createHash('sha256').update(data).digest('hex');
  const signed = hex(page.hmac(new Uint8Array(k), new Uint8Array(data))) === crypto.createHmac('sha256', k).update(data).digest('hex');
  if (sealed.equals(Buffer.from(ours)) && hashed && signed) agree++;
  else console.log('     differs from OpenSSL at ' + length + ' bytes');
}
same('SHA-256, HMAC and ChaCha20 are those of OpenSSL for random bytes of ' + lengths.length + ' lengths up to 2 MiB and a name', lengths.length, agree);

const fixture = {};
for (const line of fs.readFileSync(process.env.TERN_FIXTURE, 'utf8').split('\n')) {
  if (line && !line.startsWith('#')) fixture[line.split(' ')[0]] = line.split(' ')[1] || '';
}
const master = page.hmac(page.utf8(fixture.code), page.utf8('tern handoff v1'));
same('the master key of the fixture', fixture.master, hex(master));
same('the key that seals', fixture.enc, hex(page.hmac(master, page.utf8('enc'))));
same('the key that signs', fixture.mac, hex(page.hmac(master, page.utf8('mac'))));
for (const thing of ['links', 'file', 'empty', 'blocks']) {
  const kind = Number(fixture[thing + '.kind']);
  const nonce = unhex(fixture[thing + '.nonce']);
  const plain = unhex(fixture[thing + '.plain']);
  same('the page seals ' + thing + ' to the very bytes of the fixture', fixture[thing + '.sealed'], page.inBase64(page.seal(fixture.code, kind, nonce, plain)));
  const sealed = new Uint8Array(Buffer.from(fixture[thing + '.sealed'], 'base64url'));
  const end = sealed.length - 32;
  same('the tag of ' + thing + ' in the fixture is the one the page makes', hex(sealed.subarray(end)), hex(page.hmac(unhex(fixture.mac), sealed.subarray(0, end))));
  same('the page opens ' + thing + ' of the fixture', fixture[thing + '.plain'], hex(page.chacha20(unhex(fixture.enc), sealed.subarray(1, 13), 1, sealed.subarray(13, end))));
}

same('a code is read in both cases, with spaces and dashes anywhere', 'k4mzq7wdx2nph5tcr3vb', page.codeFrom(' K4MZ-q7wd  x2np-H5TC r3vb- '));
for (const wrong of ['k4mz q7wd x2np h5tc r0vb', 'k4mz q7wd x2np h5tc r1vb', 'k4mz q7wd x2np h5tc r8vb', 'k4mz q7wd x2np h5tc r9vb', 'k4mz_q7wd', 'k4mz.q7wd', 'k4mz\tq7wd', 'k4m\u00e9', 'k4m\u212a', 'k4m\u0131', 'k4mz\u2013q7wd']) {
  same('a code with ' + JSON.stringify(wrong) + ' is refused and not guessed at', null, page.codeFrom(wrong));
}
same('a code that is short is handed on for its length to be seen', 'k4mz', page.codeFrom('k4mz'));

same('text becomes UTF-8', Buffer.from('K\u00f6ln \u6771\u4eac \ud83d\ude00 a', 'utf8').toString('hex'), hex(page.utf8('K\u00f6ln \u6771\u4eac \ud83d\ude00 a')));
same('half a pair becomes the replacement character', 'efbfbd61efbfbd', hex(page.utf8('\ud83da\ude00')));
same('base64 for addresses, without padding', Buffer.from(key).toString('base64url') + '|' + Buffer.from(key.subarray(0, 31)).toString('base64url') + '|' + Buffer.from(key.subarray(0, 30)).toString('base64url') + '|',
  page.inBase64(key) + '|' + page.inBase64(key.subarray(0, 31)) + '|' + page.inBase64(key.subarray(0, 30)) + '|' + page.inBase64(new Uint8Array(0)));
const big = crypto.randomBytes(100001);
same('base64 of 100001 bytes', big.toString('base64url'), page.inBase64(new Uint8Array(big)));
same('a file has its name in front', '04612e6a73' + '0102', hex(page.named('a.js', unhex('0102'), 80)));
const cut = page.named('n'.repeat(79) + '\u6771.json', unhex('ff'), 80);
same('a name is cut to 80 bytes and not inside a character', '4f' + '6e'.repeat(79) + 'ff', hex(cut));
same('a name of 80 bytes stays whole', 80, page.named('n'.repeat(77) + '\u6771', unhex(''), 80)[0]);

console.log(failed ? failed + ' checks FAILED' : 'every check of the script passed');
process.exit(failed ? 1 : 0);
JS

if [ "$BROWSER" = no ]; then
  echo "part two, the page in a browser, was left out and did not run"
  exit 0
fi

CHROMIUM=""
for name in chromium chromium-browser google-chrome google-chrome-stable; do
  if command -v "$name" > /dev/null; then CHROMIUM="$(command -v "$name")"; break; fi
done
[ -n "$CHROMIUM" ] || { echo "FAIL no Chromium was found, so the page did not run in a browser"; exit 1; }
export TERN_CHROMIUM="$CHROMIUM"

node - <<'JS'
'use strict';
const crypto = require('crypto');
const fs = require('fs');
const { spawn } = require('child_process');
const readline = require('readline');

let failed = 0;
function check(what, ok, detail) {
  if (!ok) failed++;
  console.log((ok ? 'ok   ' : 'FAIL ') + what + (ok || detail === undefined ? '' : ': ' + detail));
}
const same = (what, expected, got) => check(what, JSON.stringify(expected) === JSON.stringify(got), 'expected ' + JSON.stringify(expected) + ' and got ' + JSON.stringify(got));
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const work = process.env.TERN_WORK;
const children = [];

async function handoff() {
  const java = spawn(process.env.TERN_JAVA, ['-Dstdout.encoding=UTF-8', '-cp', process.env.TERN_CLASSES, 'io.github.munzzyy.tern.core.handoff.PageToolKt', 'serve'], { stdio: ['pipe', 'pipe', 'inherit'] });
  children.push(java);
  const lines = readline.createInterface({ input: java.stdout })[Symbol.asyncIterator]();
  const said = {};
  for (let i = 0; i < 3; i++) {
    const line = (await lines.next()).value;
    said[line.split(' ')[0]] = line.substring(line.indexOf(' ') + 1);
  }
  said.take = async () => {
    java.stdin.write('take\n');
    const taken = [];
    for (;;) {
      const line = (await lines.next()).value;
      if (line === undefined || line.startsWith('end ')) return taken;
      taken.push(line);
    }
  };
  return said;
}

async function browser() {
  const chromium = spawn(process.env.TERN_CHROMIUM, [
    '--headless=new', '--remote-debugging-port=0', '--user-data-dir=' + work + '/profile', '--no-first-run', '--no-default-browser-check',
    '--disable-extensions', '--disable-background-networking', '--disable-component-update', '--disable-sync', '--disable-gpu',
    ...(process.env.TERN_CHROMIUM_FLAGS || '').split(' ').filter((flag) => flag), 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  children.push(chromium);
  const address = await new Promise((found, lost) => {
    let heard = '';
    chromium.stderr.on('data', (chunk) => {
      heard += chunk;
      const match = /DevTools listening on (ws:\S+)/.exec(heard);
      if (match) found(match[1]);
    });
    chromium.on('exit', () => lost(new Error('Chromium ended before it listened: ' + heard)));
    setTimeout(() => lost(new Error('Chromium did not listen within 30 seconds: ' + heard)), 30000);
  });
  const socket = new WebSocket(address);
  await new Promise((opened, lost) => {
    socket.onopen = opened;
    socket.onerror = () => lost(new Error('no connection to ' + address));
  });
  let last = 0;
  const waiting = new Map();
  const events = [];
  socket.onmessage = (message) => {
    const data = JSON.parse(message.data);
    if (data.id === undefined) return events.push(data);
    const { done, lost, method } = waiting.get(data.id);
    waiting.delete(data.id);
    if (data.error) lost(new Error(method + ': ' + data.error.message));
    else done(data.result);
  };
  const call = (method, params, sessionId) => new Promise((done, lost) => {
    waiting.set(++last, { done, lost, method });
    socket.send(JSON.stringify({ id: last, method, params: params || {}, sessionId }));
  });
  return { call, events };
}

async function tab(chrome) {
  const { targetId } = await chrome.call('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await chrome.call('Target.attachToTarget', { targetId, flatten: true });
  const call = (method, params) => chrome.call(method, params, sessionId);
  for (const domain of ['Page', 'Runtime', 'Network', 'Log', 'Audits', 'DOM']) await call(domain + '.enable');
  await call('Page.addScriptToEvaluateOnNewDocument', {
    source: "window.ternViolations = []; document.addEventListener('securitypolicyviolation', function (e) { window.ternViolations.push(e.violatedDirective + ' ' + e.blockedURI); });",
  });
  const mine = () => chrome.events.filter((event) => event.sessionId === sessionId);
  const run = async (expression) => {
    const { result, exceptionDetails } = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (exceptionDetails) throw new Error(expression + ': ' + JSON.stringify(exceptionDetails));
    return result.value;
  };
  const until = async (what, expression) => {
    for (let i = 0; i < 300; i++) {
      if (await run(expression)) return;
      await sleep(100);
    }
    throw new Error('waited 30 seconds for ' + what);
  };
  const open = async (url) => {
    const loads = mine().filter((event) => event.method === 'Page.loadEventFired').length;
    await call('Page.navigate', { url });
    for (let i = 0; i < 300 && mine().filter((event) => event.method === 'Page.loadEventFired').length === loads; i++) await sleep(100);
  };
  const pick = async (path) => {
    const { root } = await call('DOM.getDocument');
    const { nodeId } = await call('DOM.querySelector', { nodeId: root.nodeId, selector: '#file' });
    await call('DOM.setFileInputFiles', { files: [path], nodeId });
  };
  const requests = () => mine().filter((event) => event.method === 'Network.requestWillBeSent').map((event) => event.params);
  const violations = async () => (await run('window.ternViolations')).concat(
    mine().filter((event) => event.method === 'Audits.issueAdded' && event.params.issue.code === 'ContentSecurityPolicyIssue').map((event) => 'issue ' + JSON.stringify(event.params.issue.details)),
    mine().filter((event) => event.method === 'Log.entryAdded' && event.params.entry.source === 'security').map((event) => 'log ' + event.params.entry.text),
    mine().filter((event) => event.method === 'Runtime.exceptionThrown').map((event) => 'exception ' + JSON.stringify(event.params.exceptionDetails)),
  );
  return { call, run, until, open, pick, requests, violations };
}

const shown = (id) => "getComputedStyle(document.getElementById('" + id + "')).display !== 'none'";
const text = (id) => "document.getElementById('" + id + "').textContent";
const click = (form) => "document.querySelector('#" + form + " button').click()";
const type = (id, value) => "document.getElementById('" + id + "').value = " + JSON.stringify(value);

async function main() {
  if (typeof WebSocket === 'undefined') throw new Error('this node has no WebSocket; part two needs node 22 or newer');
  const device = await handoff();
  const code = device.code.split(' ').join('');
  console.log('a handoff is open at ' + device.address);
  const chrome = await browser();

  const scanned = await tab(chrome);
  await scanned.open(device.scan);
  await scanned.until('the page to show what it sends', shown('sender'));
  same('after a scan the address has lost the code', [device.address + '/', ''], await scanned.run('[location.href, location.hash]'));
  check('after a scan nothing asks for the code', !(await scanned.run(shown('code-part'))));
  check('the sentence for a browser without scripts is not shown', !(await scanned.run("document.body.innerText.includes('needs scripts')")));
  const links = ['https://github.com/example/wren', 'codeberg.org/example/dunnock', 'https://example.org/\u6771\u4eac?q=\u00fc'];
  await scanned.run(type('links', '  ' + links[0] + '  \r\n\r\n' + links[1] + '\n' + links[2] + '\n'));
  await scanned.run(click('links-form'));
  await scanned.until('an answer to the links', text('links-said') + " !== ''");
  same('the page says what the device answered', 'The links have arrived. Look at them on the other device.', await scanned.run(text('links-said')));
  same('and marks it as good', 'notice good', await scanned.run("document.getElementById('links-said').className"));
  same('the links are gone from the field', '', await scanned.run("document.getElementById('links').value"));
  same('the links arrived as they were typed', links.map((link) => 'link ' + link), await device.take());

  await scanned.run(type('links', 'https://example.org/second'));
  await scanned.run(click('links-form'));
  await scanned.until('an answer to the second links', "document.getElementById('links').value === ''");
  same('a second send needs no code typed', ['link https://example.org/second'], await device.take());

  await scanned.run(type('links', Array.from({ length: 21 }, (_, i) => 'https://example.org/app-' + i).join('\n')));
  const before = scanned.requests().length;
  await scanned.run(click('links-form'));
  same('21 links are refused by the page', 'Those are more than 20 links, so none of them was taken. Send them in lots of 20 or fewer.', await scanned.run(text('links-said')));
  same('and marked as bad', 'notice bad', await scanned.run("document.getElementById('links-said').className"));
  same('without a request', before, scanned.requests().length);

  const typed = await tab(chrome);
  await typed.open(device.address);
  await typed.until('the page to show what it sends', shown('sender'));
  check('without a scan the page asks for the code', await typed.run(shown('code-part')));
  const exact = crypto.randomBytes(2 * 1024 * 1024);
  fs.writeFileSync(work + '/exactly-2-MiB.json', exact);
  fs.writeFileSync(work + '/one-byte-more.json', Buffer.concat([exact, Buffer.from([1])]));
  const name = 'l\u00e4nger-' + 'n'.repeat(90) + '.json';
  fs.writeFileSync(work + '/' + name, '{"format":"tern-export","schema":1,"apps":[]}\n');

  await typed.pick(work + '/exactly-2-MiB.json');
  for (const [wrong, sentence] of [
    ['', 'The code has 20 letters and digits. Type all of them.'],
    [device.code.substring(0, 23), 'The code has 20 letters and digits. Type all of them.'],
    [device.code + 'a', 'The code has 20 letters and digits. Type all of them.'],
    [device.code.substring(0, 23) + '0', 'The code has letters and the digits 2 to 7 and nothing else. Look at the other device and type it again.'],
    [device.code.substring(0, 23) + '1', 'The code has letters and the digits 2 to 7 and nothing else. Look at the other device and type it again.'],
    [device.code.substring(0, 23) + '8', 'The code has letters and the digits 2 to 7 and nothing else. Look at the other device and type it again.'],
    [device.code.split(' ').join('_'), 'The code has letters and the digits 2 to 7 and nothing else. Look at the other device and type it again.'],
  ]) {
    await typed.run(type('code', wrong));
    await typed.run(click('file-form'));
    same('the code ' + JSON.stringify(wrong.replace(device.code.substring(0, 23), '...')) + ' is refused in a sentence', sentence, await typed.run(text('code-said')));
  }
  same('a code that cannot be right makes no request', 1, typed.requests().length);

  const other = code.substring(0, 19) + (code[19] === 'a' ? 'b' : 'a');
  await typed.run(type('code', other));
  await typed.run(click('file-form'));
  await typed.until('an answer to the file under another code', text('file-said') + " !== ''");
  same('another code does not open', 'That did not open with the code the other device shows. Type the code again.', await typed.run(text('file-said')));
  check('and the page still asks for the code', await typed.run(shown('code-part')));
  same('and nothing arrived', [], await device.take());

  await typed.run(type('code', '  ' + device.code.toUpperCase().split(' ').join('-') + ' '));
  await typed.run(click('file-form'));
  await typed.until('an answer to the file', "document.getElementById('file-said').textContent.includes('arrived')");
  same('the page says what the device answered', 'The file has arrived. Look at it on the other device.', await typed.run(text('file-said')));
  check('after a send that opened nothing asks for the code', !(await typed.run(shown('code-part'))));
  same('and the code is gone from its field', '', await typed.run("document.getElementById('code').value"));
  same('a file of exactly 2 MiB arrived whole', ['file 2097152 ' + crypto.createHash('sha256').update(exact).digest('hex') + ' exactly-2-MiB.json'], await device.take());

  await typed.pick(work + '/' + name);
  await typed.run(click('file-form'));
  await typed.until('the second file to arrive', "document.getElementById('file').value === ''");
  const arrived = await device.take();
  const small = '{"format":"tern-export","schema":1,"apps":[]}\n';
  same('a second file needs no code typed, and its name is cut to 80 bytes', ['file ' + Buffer.byteLength(small) + ' ' + crypto.createHash('sha256').update(small).digest('hex') + ' l\u00e4nger-' + 'n'.repeat(72)], arrived);

  await typed.pick(work + '/one-byte-more.json');
  const sent = typed.requests().length;
  await typed.run(click('file-form'));
  same('2 MiB and one byte are refused by the page', 'The file is larger than 2 MiB, which is more than a list of apps takes, so it was not taken.', await typed.run(text('file-said')));
  same('without a request', sent, typed.requests().length);

  const without = await tab(chrome);
  await without.call('Emulation.setScriptExecutionDisabled', { value: true });
  await without.open(device.address);
  check('with scripts turned off the page says that it needs them', await without.run("document.body.innerText.includes('This page needs scripts to seal what you send')"));
  check('and offers nothing to send', !(await without.run(shown('sender'))) && !(await without.run("document.body.innerText.includes('Send the')")));
  same('and asks for nothing but itself', ['GET ' + device.address + '/'], without.requests().map((request) => request.request.method + ' ' + request.request.url));

  for (const [which, page] of [['scanned', scanned], ['typed', typed]]) {
    const asked = page.requests().map((request) => request.request.method + ' ' + request.request.url);
    const wanted = asked.filter((request, i) => request === (i === 0 ? 'GET ' + device.address + '/' : 'POST ' + device.address + '/send'));
    same('the ' + which + ' page asked for itself and then only sent, ' + asked.length + ' requests', asked, wanted);
    check('more than the page itself was asked for', asked.length > 1);
    for (const request of page.requests().slice(1)) {
      const { postData } = await page.call('Network.getRequestPostData', { requestId: request.requestId });
      const plain = /^sealed=[A-Za-z0-9_-]+$/.test(postData) && !postData.includes(code) && !Buffer.from(postData.substring(7), 'base64url').toString('latin1').includes('example');
      check('what went over the network is one sealed field of ' + postData.length + ' bytes', plain);
      check('the address it went to holds no code', !request.request.url.includes(code) && !JSON.stringify(request.request.headers).includes(code));
    }
    same('the browser reported nothing against the policy on the ' + which + ' page', [], await page.violations());
  }

  await typed.run("var s = document.createElement('script'); s.textContent = 'window.ternSlippedIn = true'; document.body.appendChild(s); var p = document.createElement('p'); p.setAttribute('style', 'color: red'); document.body.appendChild(p);");
  await sleep(300);
  same('a script that is not the one of the page does not run', undefined, await typed.run('window.ternSlippedIn'));
  const stopped = await typed.violations();
  check('and the browser reports it, so the policy is at work: ' + JSON.stringify(stopped.filter((v) => !v.startsWith('issue'))), stopped.some((v) => v.startsWith('script-src')) && stopped.some((v) => v.startsWith('style-src')));
  const asked = await typed.run("new Promise(function (done) { var r = new XMLHttpRequest(); r.open('GET', 'http://127.0.0.1:9/'); r.onerror = function () { done('stopped'); }; r.onload = function () { done('sent'); }; r.send(); })");
  same('the page can ask nothing of another address', 'stopped', asked);
  check('and the browser reports that too', (await typed.violations()).some((v) => v.startsWith('connect-src')));
}

main().then(() => 0, (problem) => {
  console.log('FAIL ' + (problem.stack || problem));
  return 1;
}).then((broke) => {
  for (const child of children) child.kill();
  console.log(failed || broke ? 'the page in the browser FAILED' : 'every check of the page in the browser passed');
  process.exit(failed || broke ? 1 : 0);
});
JS
