package ai.openonion.oochat.ui.dashboard

import java.security.SecureRandom

/**
 * Builds the document a [DashboardScreen] WebView actually loads: a page *we*
 * own, carrying an authoritative CSP (fresh per-render nonce) and the one-way
 * click bridge, with the agent's `dashboard.html` as body content. Pure — no
 * Android types — so every claim below is directly unit-testable.
 *
 * Ported from oo-chat-web's `components/dashboard/build-srcdoc.ts`; the
 * security model is deliberately identical.
 *
 * ## Why there is no HTML sanitiser
 *
 * The agent's HTML is untrusted, and it is contained by two browser-enforced
 * layers rather than by rewriting it:
 *
 * 1. **An opaque origin.** The web client uses `sandbox="allow-scripts"`;
 *    Android's equivalent is `loadDataWithBaseURL(null, …)`, which loads the
 *    document under a null origin with no same-origin peer, no file access and
 *    no reachable app storage. See [DashboardWebView] for the settings that
 *    close the rest.
 * 2. **The CSP below.** `default-src 'none'` blocks every network egress
 *    class, and `script-src 'nonce-…'` means only the bridge runs — the
 *    agent's own `<script>` tags and `onclick` attributes are refused.
 *
 * `allow-scripts` / `javaScriptEnabled = true` is **not** there to run the
 * agent's code; it is there so our nonced bridge can run. Turning JavaScript
 * off instead would silently disable every skill button.
 *
 * ## Why the agent's HTML is wrapped, never edited
 *
 * The reference implementation once injected the CSP after the first
 * `<head>` match in the raw string, and agent markup could defeat that: a
 * stray `<!-- <head> -->` put the meta inside a comment and dropped the policy
 * entirely, leaving the sandbox as the only layer. Matching text to find HTML
 * structure cannot be made safe, so we do not: our head is emitted before any
 * agent byte is parsed. Browsers drop a nested `<html>`/`<head>`/`<body>` and
 * keep the children, so a full agent document renders unchanged inside the
 * wrapper — and its own CSP meta, if it has one, can only intersect with ours.
 *
 * The bridge sits in `<head>` ahead of the agent HTML for the same reason:
 * appended after it, an unclosed attribute or comment could swallow the script
 * tag and kill every button. It binds a delegated listener on `document`, so
 * it needs no DOM to exist yet.
 *
 * ## Why a dashboard does not link out
 *
 * The bridge cancels any click on an `<a href>` that is not a same-page
 * fragment, and [DashboardWebView] refuses every navigation the bridge does
 * not catch. Nothing on a dashboard is external (the CSP already blocks
 * subresources) and its only action is running a skill, so there is nothing
 * legitimate to navigate to — while a navigation would replace the page with
 * a document running under its own CSP, where scripts and network are allowed
 * again and a convincing fake could harvest whatever the user typed into it.
 */
object DashboardDocument {

    /**
     * Name the [DashboardWebView] bridge is injected under. The bridge script
     * below is the only caller; nothing else may be added to that object —
     * every `@JavascriptInterface` method is reachable by any script that
     * runs, so the interface is the attack surface.
     */
    const val BRIDGE_NAME = "OChatBridge"

    /** Everything except `script-src`, which carries the per-render nonce. */
    val CSP_DIRECTIVES = listOf(
        "default-src 'none'",
        "style-src 'unsafe-inline'",
        "img-src data:",
        "font-src data:",
    )

    fun cspMeta(nonce: String): String {
        val content = (CSP_DIRECTIVES + "script-src 'nonce-$nonce'").joinToString("; ")
        return """<meta http-equiv="Content-Security-Policy" content="$content">"""
    }

    /** What [componentStyles] falls back to when the caller has no theme to hand. */
    const val DEFAULT_SURFACE_CSS = "#ffffff"
    const val DEFAULT_ON_SURFACE_CSS = "#000000"

    /**
     * Styling for the components the bridge renders — ours, not the agent's.
     * A dashboard is written by an LLM, and if every agent picked its own look
     * most of them would be worse: the agent declares *what* to show.
     *
     * The `body` rule is the app's own surface, passed in as CSS rather than
     * read from `MaterialTheme` so this stays pure and unit-testable — the
     * Compose colour is resolved by [DashboardScreen] and handed over as a hex
     * string. It is a fallback, not an override: this block sits in our
     * `<head>`, so an agent's own `body` rule comes later in the document and
     * wins the cascade. The foreground travels with it because a background
     * alone would leave black default text on a dark surface.
     */
    fun componentStyles(
        surfaceCss: String = DEFAULT_SURFACE_CSS,
        onSurfaceCss: String = DEFAULT_ON_SURFACE_CSS
    ): String = """<style>
body { background: $surfaceCss; color: $onSurfaceCss; }

co-filter { display: block; margin: 0 0 12px; }
co-filter input {
  width: 100%; box-sizing: border-box;
  padding: 8px 10px; border: 1px solid rgba(127,127,127,.35); border-radius: 8px;
  font: inherit; color: inherit; background: transparent;
}
co-filter input:focus { outline: 2px solid rgba(127,127,127,.45); outline-offset: -1px; }
co-filter [data-ochat-count] { display: block; margin-top: 6px; font-size: .85em; opacity: .6; }
[data-ochat-hidden] { display: none !important; }

co-table { display: none; }
[data-ochat-sortable] th[data-ochat-sort] { cursor: pointer; user-select: none; }
[data-ochat-sortable] th[data-ochat-sort]::after {
  content: ''; opacity: .35; margin-left: .4em; font-size: .85em;
}
[data-ochat-sortable] th[aria-sort="ascending"]::after { content: '\2191'; opacity: .9; }
[data-ochat-sortable] th[aria-sort="descending"]::after { content: '\2193'; opacity: .9; }
</style>"""

    /**
     * The only script the CSP admits. One way out (a skill intent) and nothing
     * in: it reads the frame's own DOM and calls the bridge, never the other
     * direction.
     */
    fun bridgeScript(nonce: String): String = """<script nonce="$nonce">
document.addEventListener('click', function (e) {
  var t = e.target;
  var el = t && t.closest ? t.closest('[data-ochat-skill]') : null;
  if (el) {
    // Before the call, so <a href data-ochat-skill> cannot both run and navigate.
    e.preventDefault();
    if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.runSkill) {
      window.$BRIDGE_NAME.runSkill(
        el.getAttribute('data-ochat-skill') || '',
        el.getAttribute('data-ochat-args') || ''
      );
    }
    return;
  }
  var a = t && t.closest ? t.closest('a[href]') : null;
  if (a && (a.getAttribute('href') || '').charAt(0) !== '#') e.preventDefault();
});

// <co-filter target="#skills" placeholder="Filter skills"> — the agent declares
// that a list is filterable; we render the control and do the filtering.
function ochatFilter(host) {
  if (host.getAttribute('data-ochat-ready')) return;
  var target = document.querySelector(host.getAttribute('target') || '');
  // A filter with nothing to filter looks like a working control and does
  // nothing. Render only when the target is real.
  if (!target) return;
  host.setAttribute('data-ochat-ready', '1');

  var input = document.createElement('input');
  input.type = 'search';
  // Set as a property, never as markup: this is agent-authored text.
  input.placeholder = host.getAttribute('placeholder') || 'Filter';
  input.setAttribute('aria-label', input.placeholder);

  var count = document.createElement('span');
  count.setAttribute('data-ochat-count', '1');
  count.setAttribute('aria-live', 'polite');

  host.textContent = '';
  host.appendChild(input);
  host.appendChild(count);

  var items = [];
  for (var i = 0; i < target.children.length; i++) items.push(target.children[i]);

  function apply() {
    var q = input.value.trim().toLowerCase();
    var shown = 0;
    for (var i = 0; i < items.length; i++) {
      var hit = !q || (items[i].textContent || '').toLowerCase().indexOf(q) !== -1;
      if (hit) { items[i].removeAttribute('data-ochat-hidden'); shown++; }
      else { items[i].setAttribute('data-ochat-hidden', '1'); }
    }
    // Silent until something is typed: a count answers a question the user
    // has not asked yet.
    count.textContent = q ? (shown + ' of ' + items.length) : '';
  }

  input.addEventListener('input', apply);
  apply();
}

// <co-table target="#runs"> — the agent declares that a table is sortable; we
// make its headers controls. The column type is inferred rather than declared:
// the cells already say what they are.
function ochatTable(host) {
  if (host.getAttribute('data-ochat-ready')) return;
  var table = document.querySelector(host.getAttribute('target') || '');
  if (!table || !table.tHead || !table.tBodies.length) return;
  host.setAttribute('data-ochat-ready', '1');
  table.setAttribute('data-ochat-sortable', '1');

  var body = table.tBodies[0];
  var headers = table.tHead.rows.length ? table.tHead.rows[0].cells : [];

  function sortBy(index, dir) {
    var rows = [];
    for (var i = 0; i < body.rows.length; i++) rows.push(body.rows[i]);
    // Read every cell once, before sorting: a comparator that re-reads the
    // DOM on each comparison is where a few hundred rows get slow.
    var keyed = rows.map(function (row) {
      var cell = row.cells[index];
      return { row: row, text: (cell ? cell.textContent : '') || '' };
    });
    var numeric = keyed.every(function (k) {
      return k.text.trim() === '' || !isNaN(parseFloat(k.text.replace(/[,%${'$'}\s]/g, '')));
    });
    keyed.sort(function (a, b) {
      var cmp;
      if (numeric) {
        cmp = parseFloat(a.text.replace(/[,%${'$'}\s]/g, '') || '0')
            - parseFloat(b.text.replace(/[,%${'$'}\s]/g, '') || '0');
      } else {
        // localeCompare so 'B' sorts after 'a', which is what a reader expects.
        cmp = a.text.trim().toLowerCase().localeCompare(b.text.trim().toLowerCase());
      }
      return dir === 'descending' ? -cmp : cmp;
    });
    for (var j = 0; j < keyed.length; j++) body.appendChild(keyed[j].row);

    for (var h = 0; h < headers.length; h++) {
      if (h === index) headers[h].setAttribute('aria-sort', dir);
      else headers[h].removeAttribute('aria-sort');
    }
  }

  for (var c = 0; c < headers.length; c++) {
    (function (index) {
      var th = headers[index];
      th.setAttribute('data-ochat-sort', '1');
      th.setAttribute('tabindex', '0');
      th.setAttribute('role', 'button');
      th.addEventListener('click', function () {
        var next = th.getAttribute('aria-sort') === 'ascending'
          ? 'descending' : 'ascending';
        sortBy(index, next);
      });
    })(c);
  }
}

function ochatComponents() {
  var filters = document.querySelectorAll('co-filter');
  for (var i = 0; i < filters.length; i++) ochatFilter(filters[i]);
  var tables = document.querySelectorAll('co-table');
  for (var j = 0; j < tables.length; j++) ochatTable(tables[j]);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', ochatComponents);
} else {
  ochatComponents();
}
</script>"""

    /**
     * Wrap agent HTML in a document we control: our `<head>` (charset,
     * viewport, CSP, bridge) followed by the agent's markup verbatim as body
     * content. The agent HTML is never rewritten — no regex, no parsing,
     * nothing a hand-crafted string can steer. charset and viewport are ours
     * because the agent's own copies land in `<body>`, where a browser may
     * ignore them.
     */
    fun build(
        html: String,
        nonce: String,
        surfaceCss: String = DEFAULT_SURFACE_CSS,
        onSurfaceCss: String = DEFAULT_ON_SURFACE_CSS
    ): String = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${cspMeta(nonce)}
${componentStyles(surfaceCss, onSurfaceCss)}
${bridgeScript(nonce)}
</head>
<body>
$html
</body>
</html>"""

    /**
     * 128 bits of hex from [SecureRandom]. Unguessable and fresh per render is
     * the whole point: a reused or predictable nonce would let one dashboard
     * write a `<script nonce=…>` the next render admits.
     */
    fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
