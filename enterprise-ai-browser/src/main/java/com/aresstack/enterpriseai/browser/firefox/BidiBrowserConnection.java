package com.aresstack.enterpriseai.browser.firefox;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.aresstack.enterpriseai.browser.api.engine.RawTab;
import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import de.bund.zrb.WDWebSocketImpl;
import de.bund.zrb.WebDriver;
import de.bund.zrb.command.request.parameters.input.sourceActions.SourceActions;
import de.bund.zrb.command.request.parameters.browsingContext.CaptureScreenshotParameters;
import de.bund.zrb.command.response.WDBrowsingContextResult;
import de.bund.zrb.type.browsingContext.WDBrowsingContext;
import de.bund.zrb.type.browsingContext.WDInfo;
import de.bund.zrb.type.browsingContext.WDReadinessState;
import de.bund.zrb.type.script.WDEvaluateResult;
import de.bund.zrb.type.script.WDPrimitiveProtocolValue;
import de.bund.zrb.type.script.WDRemoteValue;
import de.bund.zrb.type.script.WDTarget;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One WebDriver BiDi connection (one WebSocket, one session) to a Firefox of this application, through WD4J.
 * Opening a connection starts no process and opens no tab; closing it leaves the browser running.
 */
final class BidiBrowserConnection implements BrowserConnection {

    /** Url, title and visible text in one evaluation, as a JSON array. */
    static final String READ_SCRIPT = "JSON.stringify([location.href, document.title, "
            + "document.body ? document.body.innerText : ''])";
    static final int MAX_RAW_LINKS = 2000;
    /**
     * The anchors of the rendered page (document order, at most {@value #MAX_RAW_LINKS}) as a JSON array of
     * [text, resolved href, title, aria-label, landmark region]; {@code a.href} is already absolute. Only plain values leave the page.
     */
    static final String LINKS_SCRIPT = "JSON.stringify(Array.prototype.slice.call("
            + "document.querySelectorAll('a[href], area[href]'), 0, " + MAX_RAW_LINKS + ").map(function (a) {"
            + " var t = (a.innerText || a.textContent || '').replace(/\\s+/g, ' ').trim();"
            + " if (!t) { var img = a.querySelector('img[alt]'); t = img ? img.getAttribute('alt') : ''; }"
            + " var l = a.closest ? a.closest('nav, [role=navigation], header, [role=banner], footer,"
            + " [role=contentinfo], aside, [role=complementary], main, [role=main], article') : null, region = '';"
            + " if (l) { var n = l.tagName.toLowerCase(), r = l.getAttribute('role') || '';"
            + "  region = n === 'nav' || r === 'navigation' ? 'nav' : n === 'header' || r === 'banner' ? 'header'"
            + "   : n === 'footer' || r === 'contentinfo' ? 'footer' : n === 'aside' || r === 'complementary' ? 'aside'"
            + "   : 'main'; }"
            + " return [t, String(a.href || ''), a.getAttribute('title') || '', a.getAttribute('aria-label') || '',"
            + " region]; }))";
    /** A random id stored on the window: it lives exactly as long as the document. */
    static final String DOCUMENT_TOKEN_SCRIPT = "window.__enterpriseAiDocument || (window.__enterpriseAiDocument = "
            + "String(Date.now()) + '-' + Math.random().toString(36).slice(2))";
    static final int MAX_ROOTS = 200;
    /**
     * Helpers shared by the control scripts: {@code eaiAll(selector)} queries the document, its open shadow roots
     * and same-origin frames (at most {@value #MAX_ROOTS} roots), {@code eaiFind(ref)} finds a tagged control in any
     * of them, {@code eaiOffset(e)} is the top-left of e's frame in the top window's viewport and
     * {@code eaiScope(e)} the root that resolves ids next to e (its shadow root or document).
     */
    static final String DEEP = " function eaiRoots() { var roots = [], queue = [document];"
            + "  while (queue.length && roots.length < " + MAX_ROOTS + ") { var root = queue.shift(); roots.push(root);"
            + "   var all = root.querySelectorAll('*');"
            + "   for (var q = 0; q < all.length; q++) { var el = all[q]; if (el.shadowRoot) queue.push(el.shadowRoot);"
            + "    if (el.tagName === 'IFRAME' || el.tagName === 'FRAME') { try { var fd = el.contentDocument;"
            + "     if (fd && fd.documentElement) queue.push(fd); } catch (x) { } } } }"
            + "  return roots; }"
            + " function eaiAll(sel) { var found = []; eaiRoots().forEach(function (root) {"
            + "  Array.prototype.push.apply(found, root.querySelectorAll(sel)); }); return found; }"
            + " function eaiFind(ref) { var sel = '[data-eai-control=' + JSON.stringify(String(ref)) + ']';"
            + "  var hit = document.querySelector(sel); if (hit) return hit; var roots = eaiRoots();"
            + "  for (var q = 1; q < roots.length; q++) { hit = roots[q].querySelector(sel); if (hit) return hit; } return null; }"
            + " function eaiOffset(e) { var ox = 0, oy = 0, w = e.ownerDocument.defaultView;"
            + "  while (w && w !== window && w.frameElement) { var fe = w.frameElement, fr = fe.getBoundingClientRect();"
            + "   ox += fr.left + fe.clientLeft; oy += fr.top + fe.clientTop; w = w.parent; } return [ox, oy]; }"
            + " function eaiScope(e) { var root = e.getRootNode ? e.getRootNode() : null;"
            + "  return root && root.getElementById ? root : e.ownerDocument; }";
    static final int MAX_RAW_CONTROLS = 400;
    /**
     * The visible, enabled controls of the page, its open shadow roots and same-origin frames (at most
     * {@value #MAX_RAW_CONTROLS}) as a JSON array of
     * [ref, kind, label, value, options, form target, form name, required, locator, position]. Each element gets a {@code data-eai-control} attribute with a
     * ref (kept from an earlier list), so {@link #ACT_FUNCTION} finds it again while it is on the page. Password and hidden fields and real
     * links (those belong to the link list) are left out; only plain values leave the page.
     */
    static final String CONTROLS_SCRIPT = "(function () {" + DEEP
            + " var gen = Date.now().toString(36) + Math.random().toString(36).slice(2, 6), out = [];"
            + " var els = eaiAll('input, textarea, select, button, summary, [contenteditable=\"true\"],"
            + " [role=button], [role=menuitem], [role=tab], [role=searchbox], [role=combobox], [role=switch],"
            + " [role=checkbox], [aria-expanded], [onclick]');"
            + " function flat(t) { return (t || '').replace(/\\s+/g, ' ').trim(); }"
            + " for (var i = 0; i < els.length && out.length < " + MAX_RAW_CONTROLS + "; i++) {"
            + "  var e = els[i], tag = e.tagName.toLowerCase(), type = (e.getAttribute('type') || '').toLowerCase();"
            + "  var role = (e.getAttribute('role') || '').toLowerCase();"
            + "  if (tag === 'input' && (type === 'hidden' || type === 'password')) continue;"
            + "  if (tag === 'a' && !/^\\s*(javascript:|#|$)/i.test(e.getAttribute('href') || '')) continue;"
            + "  if (e.disabled || e.getAttribute('aria-disabled') === 'true') continue;"
            + "  var r = e.getBoundingClientRect(); if (r.width === 0 && r.height === 0) continue;"
            + "  var scope = eaiScope(e), off = eaiOffset(e);"
            + "  var hint = ((e.getAttribute('name') || '') + ' ' + (e.id || '') + ' ' + (e.getAttribute('placeholder') || '')"
            + "   + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '')"
            + "   + ' ' + (typeof e.className === 'string' ? e.className : '')"
            + "   + ' ' + (e.labels && e.labels.length ? e.labels[0].innerText : '')).toLowerCase();"
            + "  var icon = e.querySelector ? e.querySelector('svg title, svg[aria-label], img[alt], [class*=icon], [class*=fa-]') : null;"
            + "  var iconHint = icon ? ((icon.getAttribute('aria-label') || icon.getAttribute('alt') || icon.textContent || '') + ' '"
            + "   + (typeof icon.className === 'string' ? icon.className : (icon.className && icon.className.baseVal) || '')).toLowerCase() : '';"
            + "  var inSearch = !!(e.closest && e.closest('[role=search], form[action*=such i], form[action*=search i], [class*=search i], [data-component*=search i]'));"
            + "  var searchy = inSearch || /such|search|query|recherch|^q\\b|\\bq$|magnif|lupe/.test(hint + ' ' + iconHint);"
            + "  var kind;"
            + "  if (tag === 'select') kind = 'select';"
            + "  else if (tag === 'input' && (type === 'checkbox' || type === 'radio')) kind = type;"
            + "  else if (tag === 'button' || (tag === 'input' && /^(submit|button|reset|image)$/.test(type))) kind ="
            + "   e.hasAttribute('aria-expanded') ? 'toggle' : 'button';"
            // A text field stays a field even when it opens a suggestion list (comboboxes carry aria-expanded).
            + "  else if (type === 'search' || role === 'searchbox' || ((tag === 'input' || tag === 'textarea' || role === 'combobox')"
            + "   && searchy)) kind = 'search';"
            + "  else if (tag === 'input' || tag === 'textarea' || e.isContentEditable) kind = 'input';"
            + "  else if (e.hasAttribute('aria-expanded') || tag === 'summary') kind = 'toggle';"
            + "  else if (role === 'combobox') kind = 'input';"
            + "  else if (role === 'menuitem') kind = 'menu';"
            + "  else if (role === 'tab') kind = 'tab';"
            + "  else if (role === 'checkbox' || role === 'switch') kind = 'checkbox';"
            + "  else kind = 'button';"
            + "  var label = e.getAttribute('aria-label') || '';"
            + "  var byLabel = false;"
            + "  if (!label && e.labels && e.labels.length) { var lc = e.labels[0].cloneNode(true);"
            + "   Array.prototype.forEach.call(lc.querySelectorAll('select, input, textarea, button'), function (x) { x.remove(); });"
            + "   label = lc.textContent; byLabel = true; }"
            + "  if (!label && e.getAttribute('aria-labelledby')) label = e.getAttribute('aria-labelledby').split(/\\s+/)"
            + "   .map(function (id) { var l = scope.getElementById(id); return l ? l.innerText : ''; }).join(' ');"
            + "  if (!label) label = e.getAttribute('placeholder') || e.getAttribute('title') || '';"
            + "  if (!label && (kind === 'button' || kind === 'menu' || kind === 'tab' || kind === 'toggle' || kind === 'checkbox'))"
            + "   label = e.innerText || e.value || e.getAttribute('alt') || '';"
            + "  if (!label && kind === 'button' && searchy) label = 'Suche (Symbol)';"
            + "  if (!label && icon) label = icon.getAttribute('aria-label') || icon.getAttribute('alt') || flat(icon.textContent);"
            + "  if (!label) label = e.getAttribute('name') || e.id || '';"
            + "  var value = '';"
            + "  if (kind === 'checkbox' || kind === 'radio') value = (e.checked || e.getAttribute('aria-checked') === 'true') ? 'an' : 'aus';"
            + "  else if (kind === 'toggle') value = e.getAttribute('aria-expanded') === 'true' || e.parentElement && e.parentElement.open ? 'offen' : 'zu';"
            + "  else if (kind === 'select') value = e.selectedIndex >= 0 ? e.options[e.selectedIndex].text : '';"
            + "  else if (kind === 'search' || kind === 'input') value = e.isContentEditable ? e.innerText : (e.value || '');"
            + "  var options = kind === 'select' ? Array.prototype.slice.call(e.options, 0, 30)"
            + "   .map(function (o) { return flat(o.text); }).join(' | ') : '';"
            + "  var form = e.form || (e.closest ? e.closest('form') : null);"
            + "  var target = form && (kind === 'search' || kind === 'input' || kind === 'button') ? String(form.action || '') : '';"
            // An element keeps the ref it got from an earlier list, so ids of that list stay valid.
            + "  var ref = e.getAttribute('data-eai-control') || gen + '-' + out.length; e.setAttribute('data-eai-control', ref);"
            // The nearest named group wins (a fieldset inside a form), then the form itself.
            + "  function groupName(g) { if (!g) return '';"
            + "   var named = g.getAttribute('aria-labelledby') ? scope.getElementById(g.getAttribute('aria-labelledby').split(/\\s+/)[0]) : null;"
            + "   var legend = g.tagName === 'FIELDSET' ? g.querySelector(':scope > legend') : g.querySelector('h1, h2, h3, h4, [role=heading]');"
            + "   return g.getAttribute('aria-label') || (named ? named.innerText : '') || (legend ? legend.innerText : '')"
            + "    || g.getAttribute('name') || g.getAttribute('title') || ''; }"
            + "  var near = e.closest ? e.closest('fieldset, [role=group], [role=dialog], [role=search], [role=form], form') : null;"
            + "  var formName = groupName(near);"
            + "  if (!formName) formName = groupName(e.form || (near && near.parentElement && near.parentElement.closest"
            + "   ? near.parentElement.closest('form, [role=form], [role=dialog]') : null));"
            + "  var required = !!(e.required || e.getAttribute('aria-required') === 'true');"
            + "  var roles = {search: role === 'combobox' ? 'combobox' : 'searchbox', input: role === 'combobox' ? 'combobox' : 'textbox',"
            + "   button: 'button', toggle: 'button', select: 'combobox', checkbox: 'checkbox', radio: 'radio', menu: 'menuitem', tab: 'tab'};"
            + "  var name = flat(label).slice(0, 80).replace(/\"/g, \"'\");"
            + "  var locator = !name ? '' : byLabel ? 'label=\"' + name + '\"' : 'role=' + (role || roles[kind] || 'button') + ' name=\"' + name + '\"';"
            + "  var v = off[1] + r.top + r.height / 2, h = off[0] + r.left + r.width / 2, position;"
            + "  if (v > innerHeight) position = 'weiter unten'; else if (v < 0) position = 'weiter oben'; else"
            + "   position = (v < innerHeight / 3 ? 'oben' : v > innerHeight * 2 / 3 ? 'unten' : 'Mitte')"
            + "    + (h < innerWidth / 3 ? ' links' : h > innerWidth * 2 / 3 ? ' rechts' : '');"
            + "  out.push([ref, kind, flat(label), flat(value).slice(0, 200), options, target, flat(formName).slice(0, 80),"
            + "   required, locator, position]); }"
            + " return JSON.stringify(out); })()";
    /** Starts counting DOM mutations of the document once; the count lives in window.__enterpriseAiMutations. */
    static final String OBSERVE = " if (!window.__enterpriseAiObserver && window.MutationObserver) {"
            + "  window.__enterpriseAiMutations = window.__enterpriseAiMutations || 0;"
            + "  window.__enterpriseAiObserver = new MutationObserver(function (m) { window.__enterpriseAiMutations += m.length; });"
            + "  window.__enterpriseAiObserver.observe(document.documentElement,"
            + "   {subtree: true, childList: true, attributes: true, characterData: true}); }";
    /**
     * Performs an action on a tagged control; arguments are (ref, action, value). Before acting it starts counting
     * DOM mutations, so {@link #STATE_SCRIPT} can tell when a single page app has settled. Returns ok, missing,
     * disabled, nofield or nooption.
     */
    static final String ACT_FUNCTION = "(function (ref, action, value) {" + DEEP
            + " var e = eaiFind(ref);"
            + " if (!e) return 'missing';"
            + " if (e.disabled || e.getAttribute('aria-disabled') === 'true') return 'disabled';"
            + " window.__enterpriseAiMutations = 0;" + OBSERVE
            + " try { e.scrollIntoView({block: 'center'}); } catch (x) { }"
            + " function fire(type) { e.dispatchEvent(new Event(type, {bubbles: true})); }"
            + " if (action === 'fill' || action === 'submit') {"
            + "  var field = e.tagName === 'INPUT' || e.tagName === 'TEXTAREA' || e.isContentEditable;"
            + "  if (!field) { if (action === 'fill') return 'nofield'; e.click(); return 'ok'; }"
            + "  e.focus();"
            + "  if (value !== null) {"
            + "   if (e.isContentEditable) { e.textContent = value; } else {"
            + "    var ew = e.ownerDocument.defaultView || window;"
            + "    var proto = e.tagName === 'TEXTAREA' ? ew.HTMLTextAreaElement.prototype : ew.HTMLInputElement.prototype;"
            + "    var d = Object.getOwnPropertyDescriptor(proto, 'value');"
            + "    if (d && d.set) { d.set.call(e, value); } else { e.value = value; } }"
            + "   fire('input'); fire('change'); }"
            + "  if (action === 'submit') {"
            + "   var init = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};"
            + "   var go = e.dispatchEvent(new KeyboardEvent('keydown', init));"
            + "   e.dispatchEvent(new KeyboardEvent('keypress', init));"
            + "   e.dispatchEvent(new KeyboardEvent('keyup', init));"
            + "   if (go && e.form) { if (e.form.requestSubmit) { e.form.requestSubmit(); } else { e.form.submit(); } } }"
            + "  return 'ok'; }"
            + " if (action === 'select') {"
            + "  if (e.tagName !== 'SELECT') return 'nofield';"
            + "  var wanted = String(value || '').toLowerCase().trim(), hit = null;"
            + "  for (var i = 0; i < e.options.length && !hit; i++) {"
            + "   var o = e.options[i]; if (o.text.trim().toLowerCase() === wanted || o.value.toLowerCase() === wanted) hit = o; }"
            + "  for (var j = 0; j < e.options.length && !hit; j++) {"
            + "   if (e.options[j].text.toLowerCase().indexOf(wanted) >= 0) hit = e.options[j]; }"
            + "  if (!hit) return 'nooption';"
            + "  e.value = hit.value; fire('input'); fire('change'); return 'ok'; }"
            + " e.click(); return 'ok'; })";
    /**
     * Prepares a real pointer or keyboard action on a tagged control; arguments are (ref). Scrolls it to the middle of
     * the viewport, starts counting DOM mutations and answers a JSON array [status, x, y, field, cover]: status ok,
     * missing, disabled or covered; x/y the viewport centre of the element; field whether it takes typed text; cover
     * the label of an element lying over the centre (a banner, an overlay).
     */
    static final String PREPARE_FUNCTION = "(function (ref) {" + DEEP
            + " var e = eaiFind(ref);"
            + " if (!e) return JSON.stringify(['missing']);"
            + " if (e.disabled || e.getAttribute('aria-disabled') === 'true') return JSON.stringify(['disabled']);"
            + " window.__enterpriseAiMutations = 0;" + OBSERVE
            + " try { e.scrollIntoView({block: 'center', inline: 'center'}); } catch (x) { }"
            // Local centre for the hit test inside e's own frame or shadow root, global centre for the pointer.
            + " var r = e.getBoundingClientRect(), lx = r.left + r.width / 2, ly = r.top + r.height / 2;"
            + " var off = eaiOffset(e), x = off[0] + lx, y = off[1] + ly, lw = e.ownerDocument.defaultView || window;"
            + " var field = (e.tagName === 'INPUT' && !/^(submit|button|reset|image|checkbox|radio|file)$/i.test(e.type))"
            + "  || e.tagName === 'TEXTAREA' || e.isContentEditable;"
            + " if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight || lx < 0 || ly < 0 || lx >= lw.innerWidth"
            + "  || ly >= lw.innerHeight) return JSON.stringify(['offscreen']);"
            + " var root = e.getRootNode && e.getRootNode().elementFromPoint ? e.getRootNode() : e.ownerDocument;"
            + " var hit = root.elementFromPoint(lx, ly);"
            + " var own = hit && (hit === e || e.contains(hit) || hit.contains(e) && hit.tagName === 'LABEL'"
            + "  || hit.closest && hit.closest('label') && hit.closest('label').control === e);"
            + " if (!own && hit) { var c = hit.closest('[role=dialog], [aria-modal=true], [id], [class]') || hit;"
            + "  return JSON.stringify(['covered', x, y, field, (c.getAttribute('aria-label') || c.innerText || c.id || '')"
            + "   .replace(/\\s+/g, ' ').trim().slice(0, 80)]); }"
            + " return JSON.stringify(['ok', x, y, field, '']); })";
    /** The current text of a tagged field, to check that typing arrived. */
    static final String VALUE_FUNCTION = "(function (ref) {" + DEEP
            + " var e = eaiFind(ref);"
            + " return e ? String(e.isContentEditable ? e.innerText : e.value || '') : ''; })";
    /**
     * Finds the visible option of an opened list box best matching (ref, value): exact text first, then containing
     * text; tags it as {@code <ref>-option} and answers that ref, or an empty string.
     */
    static final String OPTION_FUNCTION = "(function (ref, value) {" + DEEP
            + " var wanted = String(value || '').toLowerCase().trim(), exact = null, part = null;"
            + " var opts = eaiAll('[role=option], [role=menuitemradio], [role=menuitem], [role=treeitem]');"
            + " for (var i = 0; i < opts.length; i++) { var o = opts[i], r = o.getBoundingClientRect();"
            + "  if (r.width === 0 && r.height === 0) continue;"
            + "  var t = (o.innerText || o.textContent || '').replace(/\\s+/g, ' ').trim().toLowerCase();"
            + "  if (!exact && t === wanted) exact = o; if (!part && t.indexOf(wanted) >= 0) part = o; }"
            + " var hit = exact || part; if (!hit) return '';"
            + " hit.setAttribute('data-eai-control', ref + '-option'); return ref + '-option'; })";
    /** Upper bound for waiting until an opened list box shows its options. */
    static final long OPTION_WAIT_MILLIS = 2000L;
    /** WebDriver key values (Unicode private use area) of the keys typing needs. */
    static final String KEY_CONTROL = "\uE009";
    static final String KEY_BACKSPACE = "\uE003";
    static final String KEY_ENTER = "\uE007";
    /** Texts longer than this are set through the field's value instead of typed key by key. */
    static final int MAX_TYPED_CHARS = 500;

    /** Document token, readiness, the mutation count since the last action and the origin, as a JSON array. */
    static final String STATE_SCRIPT = "(function () {" + OBSERVE + " return JSON.stringify([(" + DOCUMENT_TOKEN_SCRIPT
            + "), document.readyState, window.__enterpriseAiMutations || 0, location.origin]); })()";
    /**
     * Consent banner and challenge of the page as a JSON array [consent, button labels, challenge]. A consent banner
     * is a visible element named or labelled like one (cookie, consent, Einwilligung; common banner ids) whose text
     * speaks of cookies or consent; a challenge is a known bot check (captcha frames, interstitial titles) or a
     * visible password field.
     */
    static final String PROBE_SCRIPT = "(function () {" + DEEP
            + " function shown(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }"
            + " var consent = false, labels = [];"
            + " var boxes = document.querySelectorAll('#onetrust-banner-sdk, #CybotCookiebotDialog, #usercentrics-root,"
            + " [id*=cookie i], [class*=cookie i], [id*=consent i], [class*=consent i], [aria-label*=cookie i],"
            + " [aria-label*=consent i], [role=dialog], [aria-modal=true]');"
            + " for (var i = 0; i < boxes.length && !consent; i++) {"
            + "  var b = boxes[i]; if (!shown(b)) continue;"
            + "  var t = (b.innerText || '').slice(0, 3000);"
            + "  if (!/cookie|einwillig|consent|zustimm|datenschutz|tracking/i.test(t)) continue;"
            + "  var buttons = b.querySelectorAll('button, [role=button], input[type=button], input[type=submit], a');"
            + "  for (var j = 0; j < buttons.length && labels.length < 6; j++) {"
            + "   var l = (buttons[j].innerText || buttons[j].value || '').replace(/\\s+/g, ' ').trim();"
            + "   if (l && l.length < 60 && shown(buttons[j])) labels.push(l); }"
            + "  consent = labels.length > 0; if (!consent) labels = []; }"
            + " var challenge = '';"
            + " if (document.querySelector('iframe[src*=recaptcha], iframe[src*=hcaptcha], iframe[src*=turnstile],"
            + " iframe[src*=\"challenges.cloudflare\"], #challenge-form, #cf-challenge-running, .g-recaptcha, .h-captcha')"
            + "  || /just a moment|attention required|verify you are human|sicherheitsüberprüfung|bist du ein mensch/i"
            + "  .test(document.title)) challenge = 'captcha';"
            + " else { var pw = document.querySelectorAll('input[type=password]');"
            + "  for (var k = 0; k < pw.length; k++) { if (shown(pw[k])) { challenge = 'login'; break; } } }"
            + " var errors = [];"
            + " function note(t) { t = (t || '').replace(/\\s+/g, ' ').trim(); if (t && t.length < 200 && errors.indexOf(t) < 0"
            + "  && errors.length < 6) errors.push(t); }"
            + " var bad = eaiAll('[aria-invalid=true]');"
            + " for (var m = 0; m < bad.length; m++) { var f = bad[m]; if (!shown(f)) continue;"
            + "  var name = f.getAttribute('aria-label') || (f.labels && f.labels.length ? f.labels[0].textContent : '')"
            + "   || f.getAttribute('placeholder') || f.getAttribute('name') || '';"
            + "  var said = (f.getAttribute('aria-errormessage') || f.getAttribute('aria-describedby') || '').split(/\\s+/)"
            + "   .map(function (id) { var d = id && eaiScope(f).getElementById(id); return d ? d.innerText : ''; }).join(' ');"
            + "  note((name ? name.replace(/\\s+/g, ' ').trim() + ': ' : '') + (said || f.validationMessage || 'ungültig')); }"
            + " var alerts = eaiAll('[role=alert], .invalid-feedback, .error-message, .field-error, .form-error');"
            + " for (var n = 0; n < alerts.length; n++) { if (shown(alerts[n])) note(alerts[n].innerText); }"
            + " return JSON.stringify([consent, labels.join(' | '), challenge, errors.join(' | ')]); })()";
    /**
     * Fetches (url, maxBytes) inside the page with its cookies and answers a JSON array: ["ok", final url, content
     * type, base64 bytes] or ["error", reason]. Same-origin rules of the page apply.
     */
    static final String FETCH_FUNCTION = "(function (url, max) {"
            + " return fetch(url, {credentials: 'include', redirect: 'follow'}).then(function (r) {"
            + "  if (!r.ok) return JSON.stringify(['error', 'HTTP ' + r.status]);"
            + "  if (+(r.headers.get('content-length') || 0) > max) return JSON.stringify(['error', 'zu groß']);"
            + "  return r.arrayBuffer().then(function (b) {"
            + "   if (b.byteLength > max) return JSON.stringify(['error', 'zu groß']);"
            + "   var u = new Uint8Array(b), s = '', c = 32768;"
            + "   for (var i = 0; i < u.length; i += c) { s += String.fromCharCode.apply(null, u.subarray(i, i + c)); }"
            + "   return JSON.stringify(['ok', r.url, r.headers.get('content-type') || '', btoa(s)]); }); })"
            + " .catch(function (e) { return JSON.stringify(['error', String(e && e.message || e)]); }); })";
    /** Upper bound for waiting after a load until scripts stopped changing the page. */
    static final long LOAD_SETTLE_MILLIS = 5000L;
    /** Upper bound when the load was replaced by a redirect chain (SSO round trips can take a while). */
    static final long REDIRECT_SETTLE_MILLIS = 15000L;
    /** Upper bound for waiting until the page settled after an action. */
    static final long SETTLE_TIMEOUT_MILLIS = 10000L;
    /** The document counts as settled after this long without a DOM change. */
    static final long QUIET_MILLIS = 600L;
    private static final long POLL_MILLIS = 100L;
    private static final Gson GSON = new Gson();
    private static final double CONNECT_TIMEOUT_MILLIS = 30000.0;

    private final WDWebSocketImpl socket;
    private final WebDriver driver;
    private final FirefoxInstance instance;
    private volatile boolean disconnected;
    /** Human variation of input, scrolling and reading; off unless the chat switched it on. */
    private volatile HumanInput human = HumanInput.off();
    /** Last pointer position per tab, start of the next mouse path. */
    private final Map<String, double[]> pointers = new java.util.concurrent.ConcurrentHashMap<String, double[]>();
    static final String PAGE_SIZE_SCRIPT = "JSON.stringify([document.documentElement ? document.documentElement"
            + ".scrollHeight : 0, window.innerHeight || 0, document.body ? (document.body.innerText || '').length : 0])";

    private BidiBrowserConnection(WDWebSocketImpl socket, WebDriver driver, FirefoxInstance instance) {
        this.socket = socket;
        this.driver = driver;
        this.instance = instance;
    }

    static BidiBrowserConnection open(int port, FirefoxInstance instance) throws BrowserException {
        WDWebSocketImpl socket = null;
        try {
            socket = new WDWebSocketImpl(URI.create("ws://127.0.0.1:" + port + "/session"), CONNECT_TIMEOUT_MILLIS);
            if (!socket.isConnected()) {
                throw new BrowserException(BrowserException.Kind.CONNECTION_LOST,
                        "Keine Verbindung zu Firefox (WebDriver BiDi)");
            }
            WebDriver driver = new WebDriver(socket);
            driver.connect("firefox");
            return new BidiBrowserConnection(socket, driver, instance);
        } catch (BrowserException e) {
            closeQuietly(socket);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(socket);
            throw new BrowserException(BrowserException.Kind.CONNECTION_LOST, "Verbindung unterbrochen", e);
        } catch (Exception e) {
            closeQuietly(socket);
            throw new BrowserException(instance.isAlive() ? BrowserException.Kind.CONNECTION_LOST
                    : BrowserException.Kind.BROWSER_CLOSED, "Verbindung zu Firefox fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isOpen() {
        return !disconnected && socket.isConnected();
    }

    @Override
    public List<RawTab> tabs() throws BrowserException {
        check();
        try {
            WDBrowsingContextResult.GetTreeResult tree = driver.browsingContext().getTree((WDBrowsingContext) null, 0L);
            List<RawTab> tabs = new ArrayList<RawTab>();
            for (WDInfo info : tree.getContexts()) {
                String id = info.getContext().value();
                tabs.add(new RawTab(id, info.getUrl(), title(id)));
            }
            return tabs;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public String createTab() throws BrowserException {
        check();
        try {
            return driver.browsingContext().create().getContext();
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public void activate(String contextId) throws BrowserException {
        check();
        try {
            driver.browsingContext().activate(contextId);
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public void close(String contextId) throws BrowserException {
        check();
        try {
            driver.browsingContext().close(contextId);
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public void navigate(String contextId, String url) throws BrowserException {
        check();
        String before = tokenOrNull(contextId);
        try {
            driver.browsingContext().navigate(url, contextId, WDReadinessState.COMPLETE);
        } catch (RuntimeException e) {
            if (!isAbortedByRedirect(e)) {
                throw classify(e);
            }
            // Possibly a script or form redirect (SAML/SSO bounce) that replaced the requested load: wait for the
            // final page; a stop without replacement still fails.
            check();
            settleRedirect(contextId, before, originOf(url), e);
            return;
        }
        // Complete is not ready: intranets and single page apps render their content after the load event.
        settle(contextId, null, LOAD_SETTLE_MILLIS);
        humanize(contextId);
    }

    @Override
    public void setHumanEmulation(boolean keys, boolean mouse) {
        HumanInput now = human;
        if (now.keys() != keys || now.mouse() != mouse) {
            human = new HumanInput(new java.util.Random(), keys, mouse);
        }
    }

    /**
     * After a load with Human Emulation on: scrolls through the page in a few uneven steps (lazy content appears),
     * lets the pointer wander over the text when mouse movements are on, scrolls back to the top and pauses as a
     * reader would. Only inside the tab (page scripts and BiDi input); failures never fail the load.
     */
    private void humanize(String contextId) {
        HumanInput now = human;
        if (!now.keys() && !now.mouse()) {
            return;
        }
        try {
            JsonArray size = JsonParser.parseString(evaluate(contextId, PAGE_SIZE_SCRIPT)).getAsJsonArray();
            int height = size.get(0).getAsInt();
            int viewport = size.get(1).getAsInt();
            int text = size.get(2).getAsInt();
            for (int step : now.scrollSteps(height, viewport)) {
                evaluate(contextId, "window.scrollBy(0, " + step + "), ''");
                sleep(now.scrollPause());
            }
            if (now.mouse()) {
                double[] at = pointerOf(contextId, now);
                driver.input().performActions(contextId,
                        java.util.Collections.singletonList(InputActions.moves(now.wander(at[0], at[1]))));
                driver.input().releaseActions(contextId);
            }
            if (now.keys()) {
                evaluate(contextId, "window.scrollTo(0, 0), ''");
                sleep(now.readingPause(text));
            }
        } catch (BrowserException | RuntimeException e) {
            // Emulation is decoration; the page is loaded either way.
        }
    }

    private double[] pointerOf(String contextId, HumanInput now) {
        double[] at = pointers.get(contextId);
        if (at == null) {
            at = now.startPoint();
            pointers.put(contextId, at);
        }
        return at;
    }

    private static void sleep(int millis) throws BrowserException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException(BrowserException.Kind.FAILED, "Warten unterbrochen", e);
        }
    }

    @Override
    public Map<String, String> navigateAll(Map<String, String> urlByContext) throws BrowserException {
        check();
        Map<String, String> failures = new java.util.LinkedHashMap<String, String>();
        Map<String, String> before = new java.util.HashMap<String, String>();
        for (Map.Entry<String, String> entry : urlByContext.entrySet()) {
            String contextId = entry.getKey();
            before.put(contextId, tokenOrNull(contextId));
            try {
                // Readiness none: the command returns once the load started, so all tabs load at the same time.
                driver.browsingContext().navigate(entry.getValue(), contextId, WDReadinessState.NONE);
            } catch (RuntimeException e) {
                BrowserException failure = classify(e);
                if (failure.kind() == BrowserException.Kind.BROWSER_CLOSED
                        || failure.kind() == BrowserException.Kind.CONNECTION_LOST) {
                    throw failure;
                }
                if (!isAbortedByRedirect(e)) {
                    failures.put(contextId, failure.getMessage());
                }
            }
        }
        Map<String, String> last = new java.util.HashMap<String, String>();
        Map<String, Long> quietSince = new java.util.HashMap<String, Long>();
        java.util.Set<String> pending = new java.util.LinkedHashSet<String>(urlByContext.keySet());
        pending.removeAll(failures.keySet());
        java.util.Set<String> replaced = new java.util.HashSet<String>();
        long deadline = System.currentTimeMillis() + REDIRECT_SETTLE_MILLIS;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            pause();
            for (java.util.Iterator<String> it = pending.iterator(); it.hasNext(); ) {
                String contextId = it.next();
                JsonArray state;
                try {
                    state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
                } catch (BrowserException | RuntimeException e) {
                    // Between two documents there is nothing to evaluate in; only a gone browser ends the wait.
                    check();
                    continue;
                }
                if (!state.get(0).getAsString().equals(before.get(contextId))) {
                    replaced.add(contextId);
                }
                String now = state.toString();
                long time = System.currentTimeMillis();
                if (!now.equals(last.get(contextId))) {
                    last.put(contextId, now);
                    quietSince.put(contextId, time);
                } else if (isLoadSettled(replaced.contains(contextId),
                        "complete".equals(state.get(1).getAsString()), time - quietSince.get(contextId))) {
                    it.remove();
                }
            }
        }
        for (String contextId : pending) {
            if (!replaced.contains(contextId)) {
                failures.put(contextId, "Seite hat nicht rechtzeitig geladen");
            }
        }
        for (String contextId : urlByContext.keySet()) {
            if (!failures.containsKey(contextId)) {
                humanize(contextId);
            }
        }
        return failures;
    }

    /** The decision of {@link #navigateAll} for one tab: a new document arrived, is complete and quiet. */
    static boolean isLoadSettled(boolean newDocument, boolean complete, long quietMillis) {
        return newDocument && complete && quietMillis >= QUIET_MILLIS;
    }

    private String tokenOrNull(String contextId) {
        try {
            return evaluate(contextId, DOCUMENT_TOKEN_SCRIPT);
        } catch (BrowserException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Waits after an aborted load until a replacement navigation arrived: the tab left {@code returnOrigin} (SSO
     * provider) or showed at least two new documents (launch page plus target), and is then quiet and complete back
     * on {@code returnOrigin}. A launch page that is quiet while the provider still answers is not taken. A tab that
     * left and stays away (login page) is taken after {@link #REDIRECT_SETTLE_MILLIS}; one that never saw a
     * replacement (the user pressed Stop) fails with the original abort.
     */
    private void settleRedirect(String contextId, String before, String returnOrigin, RuntimeException abort)
            throws BrowserException {
        long deadline = System.currentTimeMillis() + REDIRECT_SETTLE_MILLIS;
        java.util.Set<String> documents = new java.util.HashSet<String>();
        boolean left = false;
        String last = null;
        long quietSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            pause();
            JsonArray state;
            try {
                state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                check();
                continue;
            }
            String token = state.get(0).getAsString();
            String origin = state.get(3).getAsString();
            if (!token.equals(before)) {
                documents.add(token);
            }
            left |= returnOrigin != null && !returnOrigin.equals(origin);
            String now = state.toString();
            if (!now.equals(last)) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (isRedirectSettled(left, documents.size(), "complete".equals(state.get(1).getAsString()),
                    System.currentTimeMillis() - quietSince, returnOrigin == null || returnOrigin.equals(origin))) {
                return;
            }
        }
        if (!left && documents.size() < 2) {
            throw classify(abort);
        }
    }

    /** The decision of {@link #settleRedirect}: a replacement arrived and the page is quiet back on the origin. */
    static boolean isRedirectSettled(boolean left, int newDocuments, boolean complete, long quietMillis,
                                     boolean onReturnOrigin) {
        return (left || newDocuments >= 2) && complete && quietMillis >= QUIET_MILLIS && onReturnOrigin;
    }

    @Override
    public RawPage read(String contextId) throws BrowserException {
        check();
        try {
            JsonArray values = JsonParser.parseString(evaluate(contextId, READ_SCRIPT)).getAsJsonArray();
            return new RawPage(values.get(0).getAsString(), values.get(1).getAsString(), values.get(2).getAsString());
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public List<RawLink> links(String contextId) throws BrowserException {
        check();
        try {
            JsonArray values = JsonParser.parseString(evaluate(contextId, LINKS_SCRIPT)).getAsJsonArray();
            List<RawLink> links = new ArrayList<RawLink>(values.size());
            for (JsonElement value : values) {
                JsonArray link = value.getAsJsonArray();
                links.add(new RawLink(link.get(0).getAsString(), link.get(1).getAsString(), link.get(2).getAsString(),
                        link.get(3).getAsString(), link.get(4).getAsString()));
            }
            return links;
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public List<RawControl> controls(String contextId) throws BrowserException {
        check();
        try {
            JsonArray values = JsonParser.parseString(evaluate(contextId, CONTROLS_SCRIPT)).getAsJsonArray();
            List<RawControl> controls = new ArrayList<RawControl>(values.size());
            for (JsonElement value : values) {
                JsonArray c = value.getAsJsonArray();
                controls.add(new RawControl(c.get(0).getAsString(), c.get(1).getAsString(), c.get(2).getAsString(),
                        c.get(3).getAsString(), c.get(4).getAsString(), c.get(5).getAsString(), c.get(6).getAsString(),
                        c.get(7).getAsBoolean(), c.get(8).getAsString(), c.get(9).getAsString()));
            }
            return controls;
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public RawActOutcome act(String contextId, String ref, BrowserAction action, String value)
            throws BrowserException {
        check();
        String before = documentToken(contextId);
        if (action != BrowserAction.SELECT && actReally(contextId, ref, action, value, before)) {
            return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
        }
        String script = ACT_FUNCTION + "(" + GSON.toJson(ref) + ", "
                + GSON.toJson(action.name().toLowerCase(Locale.ROOT)) + ", " + GSON.toJson(value) + ")";
        String answer;
        try {
            answer = evaluate(contextId, script);
        } catch (BrowserException | RuntimeException e) {
            // A submit can replace the document before the evaluation answers; whether the action worked shows in
            // the settled state below. A browser or connection that is gone still fails there.
            check();
            answer = "ok";
        }
        if ("missing".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
        }
        if ("disabled".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist deaktiviert");
        }
        if ("nofield".equals(answer) && action == BrowserAction.SELECT) {
            return selectCustom(contextId, ref, value, before);
        }
        if ("nofield".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Diese Aktion passt nicht zum Bedienelement (fill nur für Eingabefelder, select nur für Auswahllisten)");
        }
        if ("nooption".equals(answer)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Keine passende Option: " + value);
        }
        return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
    }

    /**
     * Chooses {@code value} in a list box that is not a native select (ARIA combobox, menu button): opens it like a
     * person, by typing the value into it when it takes text or by clicking it otherwise, then clicks the visible
     * option whose text matches best.
     */
    private RawActOutcome selectCustom(String contextId, String ref, String value, String before)
            throws BrowserException {
        if (!actReally(contextId, ref, BrowserAction.SELECT, value, before)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Die Auswahlliste lässt sich nicht öffnen; browser_controls erneut aufrufen");
        }
        settle(contextId, before, OPTION_WAIT_MILLIS);
        String option;
        try {
            option = evaluate(contextId, OPTION_FUNCTION + "(" + GSON.toJson(ref) + ", " + GSON.toJson(value) + ")");
        } catch (RuntimeException e) {
            throw classify(e);
        }
        if (option.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Keine passende Option: " + value);
        }
        if (!actReally(contextId, option, BrowserAction.CLICK, null, before)) {
            evaluate(contextId, ACT_FUNCTION + "(" + GSON.toJson(option) + ", \"click\", null)");
        }
        return settle(contextId, before, SETTLE_TIMEOUT_MILLIS);
    }

    /**
     * Performs a click, fill or submit as real input, like a person: a pointer click on the element's centre, then
     * typed keys (select all, delete, the value, Enter for submit). Sites built on frameworks react only to such
     * trusted events. Answers {@code false} when real input is not possible here (element off screen, input not
     * supported), so the scripted action runs instead; fails when the element is gone, disabled or covered.
     */
    private boolean actReally(String contextId, String ref, BrowserAction action, String value, String before)
            throws BrowserException {
        JsonArray prepared;
        try {
            prepared = JsonParser.parseString(evaluate(contextId, PREPARE_FUNCTION + "(" + GSON.toJson(ref) + ")"))
                    .getAsJsonArray();
        } catch (RuntimeException e) {
            throw classify(e);
        }
        String status = prepared.get(0).getAsString();
        if ("missing".equals(status)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
        }
        if ("disabled".equals(status)) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist deaktiviert");
        }
        if ("covered".equals(status)) {
            String cover = prepared.get(4).getAsString();
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Das Bedienelement ist verdeckt"
                    + (cover.isEmpty() ? "" : " von „" + cover + "“")
                    + "; zuerst das überdeckende Element schließen (z. B. Banner), dann browser_controls erneut");
        }
        if (!"ok".equals(status)) {
            return false;
        }
        boolean field = prepared.get(3).getAsBoolean();
        if (action == BrowserAction.FILL && !field) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Diese Aktion passt nicht zum Bedienelement (fill nur für Eingabefelder, select nur für Auswahllisten)");
        }
        long x = Math.round(prepared.get(1).getAsDouble());
        long y = Math.round(prepared.get(2).getAsDouble());
        boolean typing = field && action != BrowserAction.CLICK && value != null;
        try {
            List<SourceActions> actions = new ArrayList<SourceActions>();
            HumanInput now = human;
            double[] from = pointerOf(contextId, now);
            actions.add(InputActions.click(from[0], from[1], x, y, now));
            pointers.put(contextId, new double[]{x, y});
            if (typing && value.length() <= MAX_TYPED_CHARS) {
                actions.add(InputActions.typeReplacing(value, now));
            }
            driver.input().performActions(contextId, actions);
            driver.input().releaseActions(contextId);
            if (typing && !value.equals(evaluate(contextId, VALUE_FUNCTION + "(" + GSON.toJson(ref) + ")"))) {
                // The field rejected or reformatted the keys (masks, long text); set the value as a script would,
                // then still send Enter as a real key.
                evaluate(contextId, ACT_FUNCTION + "(" + GSON.toJson(ref) + ", \"fill\", " + GSON.toJson(value) + ")");
            }
            if (field && action == BrowserAction.SUBMIT) {
                driver.input().performActions(contextId,
                        java.util.Collections.<SourceActions>singletonList(InputActions.press(KEY_ENTER)));
                driver.input().releaseActions(contextId);
            }
        } catch (RuntimeException e) {
            // A submit may replace the document while the actions are answered; then the settled state shows the
            // effect. Any other failure means the input may not have happened, so the scripted action runs instead.
            check();
            if (!documentReplaced(contextId, before)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the tab shows another document than {@code before}, or is between two documents right now. */
    private boolean documentReplaced(String contextId, String before) {
        try {
            return !before.equals(evaluate(contextId, DOCUMENT_TOKEN_SCRIPT));
        } catch (BrowserException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Waits until a new document is complete and quiet, or the old one had no DOM change for {@link #QUIET_MILLIS};
     * at most {@code timeoutMillis}, after which the page is taken as it is. {@code before} is the document token
     * before an action, {@code null} after a navigation (then only quietness counts).
     */
    private RawActOutcome settle(String contextId, String before, long timeoutMillis) throws BrowserException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String last = null;
        long quietSince = System.currentTimeMillis();
        boolean navigated = false;
        boolean changed = false;
        while (System.currentTimeMillis() < deadline) {
            pause();
            JsonArray state;
            try {
                state = JsonParser.parseString(evaluate(contextId, STATE_SCRIPT)).getAsJsonArray();
            } catch (BrowserException | RuntimeException e) {
                // Between two documents there is nothing to evaluate in; only a gone browser ends the wait.
                check();
                continue;
            }
            String token = state.get(0).getAsString();
            boolean complete = "complete".equals(state.get(1).getAsString());
            navigated |= before != null && !token.equals(before);
            changed |= state.get(2).getAsLong() > 0;
            String now = state.toString();
            if (!now.equals(last)) {
                last = now;
                quietSince = System.currentTimeMillis();
            } else if (complete && System.currentTimeMillis() - quietSince >= QUIET_MILLIS) {
                break;
            }
        }
        return new RawActOutcome(navigated, changed);
    }

    /** {@code scheme://host[:port]} of {@code url} as {@code location.origin} reports it, or {@code null}. */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            boolean usual = port < 0 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (usual ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether {@code e} only says that the requested load was replaced by another one (Firefox reports
     * {@code NS_BINDING_ABORTED} when a page redirects by script or form post before it finished loading).
     */
    static boolean isAbortedByRedirect(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.contains("NS_BINDING_ABORTED");
    }

    private static void pause() throws BrowserException {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrowserException(BrowserException.Kind.FAILED, "Warten auf die Seite unterbrochen", e);
        }
    }

    /** Id of the layer drawn over the page while a screenshot is taken. */
    static final String SHOT_LAYER = "__enterprise-ai-shot";
    /** Margin around a control when only its region is taken, in CSS pixels. */
    static final int SHOT_CLIP_MARGIN = 120;
    /** JPEG qualities tried in turn until the picture fits. */
    private static final java.util.List<Float> SHOT_QUALITIES =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(0.75f, 0.55f, 0.35f, 0.2f));
    /**
     * Prepares a screenshot; arguments are (marks: ref to label, clipRef or null, margin). Draws a fixed layer that
     * covers visible password fields and labels every marked control inside the picture, scrolls a clip control into
     * view first. Returns JSON [visible refs, clip box [x, y, width, height] in viewport CSS pixels or null] or
     * "missing" when the clip control is gone. Only plain values leave the page.
     */
    static final String SHOT_PREPARE_FUNCTION = "(function (marks, clipRef, margin) {"
            + " var old = document.getElementById('" + SHOT_LAYER + "'); if (old) old.remove();"
            + " var box = null, vw = document.documentElement.clientWidth || innerWidth,"
            + "  vh = document.documentElement.clientHeight || innerHeight;"
            + " if (clipRef) { var c = document.querySelector('[data-eai-control=\"' + clipRef + '\"]');"
            + "  if (!c) return 'missing';"
            + "  try { c.scrollIntoView({block: 'center', inline: 'center'}); } catch (x) { }"
            + "  var q = c.getBoundingClientRect(), x0 = Math.max(0, Math.floor(q.left - margin)),"
            + "   y0 = Math.max(0, Math.floor(q.top - margin)), x1 = Math.min(vw, Math.ceil(q.right + margin)),"
            + "   y1 = Math.min(vh, Math.ceil(q.bottom + margin));"
            + "  if (x1 > x0 && y1 > y0) box = [x0, y0, x1 - x0, y1 - y0]; }"
            + " var area = box ? {l: box[0], t: box[1], r: box[0] + box[2], b: box[1] + box[3]}"
            + "  : {l: 0, t: 0, r: vw, b: vh};"
            + " function inside(r) { return r.width > 0 && r.height > 0 && r.right > area.l && r.left < area.r"
            + "  && r.bottom > area.t && r.top < area.b; }"
            + " var layer = document.createElement('div'); layer.id = '" + SHOT_LAYER + "';"
            + " layer.setAttribute('style', 'position:fixed;left:0;top:0;width:0;height:0;overflow:visible;"
            + "z-index:2147483647;pointer-events:none;');"
            + " function add(style, text) { var d = document.createElement('div');"
            + "  d.setAttribute('style', 'position:fixed;box-sizing:border-box;' + style);"
            + "  if (text) d.textContent = text; layer.appendChild(d); }"
            + " var pw = document.querySelectorAll('input[type=password]');"
            + " for (var i = 0; i < pw.length; i++) { var p = pw[i].getBoundingClientRect(); if (!inside(p)) continue;"
            + "  add('left:' + p.left + 'px;top:' + p.top + 'px;width:' + p.width + 'px;height:' + p.height"
            + "   + 'px;background:#555;'); }"
            + " var seen = [];"
            + " for (var ref in marks) { if (!Object.prototype.hasOwnProperty.call(marks, ref)) continue;"
            + "  var e = document.querySelector('[data-eai-control=\"' + ref + '\"]'); if (!e) continue;"
            + "  var r = e.getBoundingClientRect(); if (!inside(r)) continue; seen.push(ref);"
            + "  var label = marks[ref]; if (!label) continue;"
            + "  add('left:' + r.left + 'px;top:' + r.top + 'px;width:' + r.width + 'px;height:' + r.height"
            + "   + 'px;border:2px solid #e8590c;');"
            + "  add('left:' + Math.max(area.l, r.left) + 'px;top:' + Math.max(area.t, r.top - 16) + 'px;"
            + "padding:0 3px;font:bold 12px/16px sans-serif;color:#fff;background:#e8590c;border-radius:3px;"
            + "white-space:nowrap;', label); }"
            + " (document.body || document.documentElement).appendChild(layer);"
            + " return JSON.stringify([seen, box]); })";
    static final String SHOT_CLEANUP_SCRIPT = "(function () { var l = document.getElementById('" + SHOT_LAYER + "');"
            + " if (l) l.remove(); return ''; })()";

    @Override
    public RawScreenshot screenshot(String contextId, Map<String, String> marks, String clipRef, int maxBytes)
            throws BrowserException {
        check();
        try {
            String prepared = evaluate(contextId, SHOT_PREPARE_FUNCTION + "("
                    + GSON.toJson(marks == null ? new LinkedHashMap<String, String>() : marks) + ", "
                    + GSON.toJson(clipRef) + ", " + SHOT_CLIP_MARGIN + ")");
            if ("missing".equals(prepared)) {
                throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                        "Das Bedienelement ist nicht mehr auf der Seite; browser_controls erneut aufrufen");
            }
            JsonArray values = JsonParser.parseString(prepared).getAsJsonArray();
            List<String> visible = new ArrayList<String>();
            for (JsonElement ref : values.get(0).getAsJsonArray()) {
                visible.add(ref.getAsString());
            }
            CaptureScreenshotParameters.ClipRectangle clip = null;
            if (!values.get(1).isJsonNull()) {
                JsonArray box = values.get(1).getAsJsonArray();
                clip = new CaptureScreenshotParameters.ClipRectangle.BoxClipRectangle(box.get(0).getAsInt(),
                        box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt());
            }
            try {
                // JPEG keeps a page picture small; a busy page on a high-density screen gets lower quality steps.
                for (float quality : SHOT_QUALITIES) {
                    WDBrowsingContextResult.CaptureScreenshotResult shot = driver.browsingContext().captureScreenshot(
                            new WDBrowsingContext(contextId), CaptureScreenshotParameters.Origin.VIEWPORT,
                            new CaptureScreenshotParameters.ImageFormat("image/jpeg", quality), clip);
                    if (shot == null || shot.getData() == null || shot.getData().isEmpty()) {
                        throw new BrowserException(BrowserException.Kind.FAILED, "Firefox lieferte kein Bild");
                    }
                    byte[] image = java.util.Base64.getDecoder().decode(shot.getData());
                    if (image.length <= maxBytes) {
                        return new RawScreenshot("image/jpeg", image, visible);
                    }
                }
                throw new BrowserException(BrowserException.Kind.FAILED, "Bildschirmfoto zu groß (über "
                        + maxBytes / 1024 + " KB); einen Ausschnitt um ein Bedienelement anfordern");
            } finally {
                try {
                    evaluate(contextId, SHOT_CLEANUP_SCRIPT);
                } catch (BrowserException | RuntimeException ignored) {
                    // The layer is pointer-transparent and goes with the document anyway.
                }
            }
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public RawDownload fetch(String contextId, String url, int maxBytes) throws BrowserException {
        check();
        try {
            JsonArray values = JsonParser.parseString(evaluate(contextId, FETCH_FUNCTION + "(" + GSON.toJson(url)
                    + ", " + maxBytes + ")")).getAsJsonArray();
            if (!"ok".equals(values.get(0).getAsString())) {
                throw new BrowserException(BrowserException.Kind.FAILED,
                        "Dokument konnte nicht geladen werden: " + values.get(1).getAsString());
            }
            return new RawDownload(values.get(1).getAsString(), values.get(2).getAsString(),
                    java.util.Base64.getDecoder().decode(values.get(3).getAsString()));
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public BrowserProbe probe(String contextId) throws BrowserException {
        check();
        try {
            JsonArray values = JsonParser.parseString(evaluate(contextId, PROBE_SCRIPT)).getAsJsonArray();
            return new BrowserProbe(values.get(0).getAsBoolean(), values.get(1).getAsString(),
                    values.get(2).getAsString(), values.get(3).getAsString());
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public String documentToken(String contextId) throws BrowserException {
        check();
        try {
            return evaluate(contextId, DOCUMENT_TOKEN_SCRIPT);
        } catch (BrowserException e) {
            throw e;
        } catch (RuntimeException e) {
            throw classify(e);
        }
    }

    @Override
    public void disconnect() {
        disconnected = true;
        try {
            driver.close();
        } catch (RuntimeException ignored) {
            // Closing anyway.
        }
        closeQuietly(socket);
    }

    private String title(String contextId) {
        try {
            return evaluate(contextId, "document.title");
        } catch (BrowserException | RuntimeException e) {
            // A tab that is still loading or shows an error page has no readable title; the list stays usable.
            return "";
        }
    }

    private String evaluate(String contextId, String script) throws BrowserException {
        WDEvaluateResult result = driver.script().evaluate(script,
                new WDTarget.ContextTarget(new WDBrowsingContext(contextId)), true);
        if (!(result instanceof WDEvaluateResult.WDEvaluateResultSuccess)) {
            throw new BrowserException(BrowserException.Kind.FAILED, "Seite konnte nicht gelesen werden");
        }
        WDRemoteValue value = ((WDEvaluateResult.WDEvaluateResultSuccess) result).getResult();
        if (value instanceof WDPrimitiveProtocolValue.StringValue) {
            return ((WDPrimitiveProtocolValue.StringValue) value).getValue();
        }
        return "";
    }

    private void check() throws BrowserException {
        if (!instance.isAlive()) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Firefox wurde geschlossen");
        }
        if (!isOpen()) {
            throw new BrowserException(BrowserException.Kind.CONNECTION_LOST, "Verbindung zu Firefox verloren");
        }
    }

    /** Process gone beats connection gone beats protocol errors. */
    private BrowserException classify(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!instance.isAlive()) {
            return new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Firefox wurde geschlossen", e);
        }
        if (!socket.isConnected()) {
            return new BrowserException(BrowserException.Kind.CONNECTION_LOST, "Verbindung zu Firefox verloren", e);
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("no such frame") || lower.contains("invalid argument")) {
            return new BrowserException(BrowserException.Kind.INVALID_REQUEST, message, e);
        }
        if (lower.contains("timeout")) {
            return new BrowserException(BrowserException.Kind.TIMEOUT, "Firefox hat nicht rechtzeitig geantwortet", e);
        }
        return new BrowserException(BrowserException.Kind.FAILED, message, e);
    }

    private static void closeQuietly(WDWebSocketImpl socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (RuntimeException ignored) {
                // Already closed.
            }
        }
    }
}
