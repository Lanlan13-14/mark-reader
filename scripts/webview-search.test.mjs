// Run: node --test scripts/webview-search.test.mjs (no Android build required).
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const root = new URL('../', import.meta.url);
const read = p => readFileSync(new URL(p, root), 'utf8');
const source = read('app/src/main/assets/renderer/search.js');
const { createSearchRenderNotifier } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
function harness() {
  let next = 0;
  const frames = new Map();
  let calls = 0;
  const notifier = createSearchRenderNotifier(() => calls++, cb => {
    frames.set(++next, cb); return next;
  }, id => frames.delete(id));
  return { notifier, get calls() { return calls; }, tick() {
    const batch = [...frames.values()]; frames.clear(); batch.forEach(cb => cb());
  } };
}
test('native re-index waits two layout frames', () => {
  const h = harness(); h.notifier.rendered();
  assert.equal(h.calls, 0); h.tick(); assert.equal(h.calls, 0);
  h.tick(); assert.equal(h.calls, 1);
});
test('consecutive renders coalesce, including render between frames', () => {
  const h = harness(); h.notifier.rendered(); h.notifier.rendered();
  h.tick(); h.notifier.rendered(); h.tick(); assert.equal(h.calls, 0);
  h.tick(); assert.equal(h.calls, 1);
});
test('dispose invalidates pending render notification', () => {
  const h = harness(); h.notifier.rendered(); h.tick(); h.notifier.dispose();
  h.tick(); assert.equal(h.calls, 0);
});
test('renderer integration never rewrites DOM for search', () => {
  assert.doesNotMatch(source, /innerHTML|createTreeWalker|createElement|replaceChild|surroundContents/);
  const host = read('app/src/main/assets/renderer/android-host.js');
  assert.match(host, /view\.on\('headings', \(\) => searchRender\.rendered\(\)\)/);
  assert.match(host, /bridge\.searchRendered\(\)/);
});
test('Android policy: native scan, serialize requests, debounce, guard stale results and disposal', () => {
  const host = read('app/src/main/java/com/markreader/webview/WebViewDocumentHost.kt');
  assert.match(host, /webView\.findAllAsync\(searchQuery\)/);
  assert.match(host, /request\.first == searchGeneration/);
  assert.match(host, /inFlightSearch != null \|\| searchQuery\.isEmpty\(\)/);
  assert.match(host, /delay\(SEARCH_DEBOUNCE_MS\)/);
  assert.match(host, /webView\.setFindListener\(null\)/);
  const next = host.slice(host.indexOf('fun findNext('), host.indexOf('private fun invalidateSearchDocument'));
  assert.match(next, /webView\.findNext\(forward\)/);
  assert.doesNotMatch(next, /findAllAsync|scheduleSearch|evaluateJavascript/);
});
test('Compose exposes Markdown search without enabling inaccurate heading offsets', () => {
  const screen = read('app/src/main/java/com/markreader/ui/screens/ViewerScreen.kt');
  assert.match(screen, /isContentSearchable = isContentIndexable \|\| readerContent is ReaderContent.Markdown/);
  assert.match(screen, /hasHeadings && isContentIndexable/);
  assert.match(screen, /onSearchResult = viewModel::onWebViewSearchResult/);
  assert.match(screen, /webViewSearchNavigation\?\.invoke\(true\)/);
  assert.match(screen, /webViewSearchNavigation\?\.invoke\(false\)/);
});

test('navigation callbacks are tagged before dispatch and drain queued taps', () => {
  const host = read('app/src/main/java/com/markreader/webview/WebViewDocumentHost.kt');
  const next = host.slice(host.indexOf('fun findNext('), host.indexOf('private fun invalidateSearchDocument'));
  assert.ok(next.indexOf('inFlightSearch = searchGeneration to searchQuery') < next.indexOf('webView.findNext(forward)'));
  assert.match(next, /pendingSearchNavigation.addLast\(forward\)/);
  assert.match(host, /findNext\(pendingSearchNavigation.removeFirst\(\)\)/);
});
test('document changes invalidate synchronously, scans await rendered layout', () => {
  const host = read('app/src/main/java/com/markreader/webview/WebViewDocumentHost.kt');
  for (const name of ['openDocument', 'showDocument']) {
    const method = host.slice(host.indexOf(`fun ${name}(`));
    assert.ok(method.indexOf('invalidateSearchDocument()') < method.indexOf('scope.launch('));
  }
  assert.match(host, /if \(destroyed \|\| !searchDocumentReady \|\| !searchReady/);
  assert.match(host, /fun searchRendered\(\) = fromHostPage \{\s+searchDocumentReady = true/);
  assert.match(host, /pendingSearchNavigation.clear\(\)/);
});
