// AskAI Research add-on, background page. The ONLY place that talks to AskAI: it long-polls the HUD state from the
// local bridge (127.0.0.1, port and token in bridge.json, written by AskAI at every browser start) and posts the
// user's HUD commands back. It also runs AskAI's read-only page scripts (structured capture, DOM fingerprint, consent
// and challenge detection, anchors) through tabs.executeScript: they run in the tab's isolated content-script world,
// so the page's own scripts see neither the script nor its globals, and no page.evaluate reaches the page's world.
// No content script is registered and no web-accessible resource exists, so a page has nothing of this add-on to
// observe.
'use strict';

const PROTOCOL = 1;
const POLL_RETRY_MIN_MS = 500;
const POLL_RETRY_MAX_MS = 5000;
/** How long a read waits for a tab showing its URL (a navigation may just be committing). */
const TAB_LOOKUP_MS = 2000;

let bridge = null;          // {base, token} once bridge.json was read
let connection = 'starting'; // starting | connected | retrying | no-bridge
let version = 0;
let hud = null;             // the last HUD state from AskAI
const panels = new Set();

async function loadBridge() {
  try {
    const response = await fetch(browser.runtime.getURL('bridge.json'), {cache: 'no-store'});
    if (!response.ok) return null;
    const config = await response.json();
    if (config.protocol !== PROTOCOL || !config.port || !config.token) return null;
    return {base: 'http://127.0.0.1:' + config.port, token: String(config.token)};
  } catch (e) {
    return null;
  }
}

function headers(extra) {
  return Object.assign({'Authorization': 'Bearer ' + bridge.token}, extra || {});
}

async function hello() {
  const response = await fetch(bridge.base + '/v1/hello', {
    method: 'POST',
    headers: headers({'Content-Type': 'application/json'}),
    body: JSON.stringify({protocol: PROTOCOL, addonVersion: browser.runtime.getManifest().version})
  });
  if (!response.ok) throw new Error('hello ' + response.status);
}

async function pollLoop() {
  let retry = POLL_RETRY_MIN_MS;
  let greeted = false;
  for (;;) {
    try {
      if (!greeted) {
        await hello();
        greeted = true;
      }
      const response = await fetch(bridge.base + '/v1/state?after=' + version,
          {headers: headers(), cache: 'no-store'});
      if (response.status === 200) {
        const body = await response.json();
        version = body.version;
        hud = body.state;
      } else if (response.status !== 204) {
        throw new Error('state ' + response.status);
      }
      setConnection('connected');
      retry = POLL_RETRY_MIN_MS;
      if (response.status === 200) publish();
    } catch (e) {
      greeted = false;
      setConnection('retrying');
      await new Promise(resolve => setTimeout(resolve, retry));
      retry = Math.min(POLL_RETRY_MAX_MS, retry * 2);
    }
  }
}

// ---------------------------------------------------------------- page reads

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function readLoop() {
  let retry = POLL_RETRY_MIN_MS;
  for (;;) {
    try {
      const response = await fetch(bridge.base + '/v1/read', {headers: headers(), cache: 'no-store'});
      if (response.status === 200) {
        const read = await response.json();
        runRead(read); // not awaited: reads of different tabs (worker user contexts) may overlap
      } else if (response.status !== 204) {
        throw new Error('read ' + response.status);
      }
      retry = POLL_RETRY_MIN_MS;
    } catch (e) {
      await sleep(retry);
      retry = Math.min(POLL_RETRY_MAX_MS, retry * 2);
    }
  }
}

// Which tab belongs to which Playwright page: AskAI sends a stable key per page. The first read of a key binds it to
// the one UNBOUND tab showing the read's URL; every later read of that key goes to exactly that tab, even when other
// tabs (other worker user contexts) show the same URL. Several unbound candidates are refused (AMBIGUOUS_TAB), never
// guessed, so a read can never return another tab's DOM.
const tabByKey = new Map();   // page key -> tab id
const keyByTab = new Map();   // tab id -> page key
browser.tabs.onRemoved.addListener(tabId => {
  const key = keyByTab.get(tabId);
  if (key !== undefined) {
    keyByTab.delete(tabId);
    tabByKey.delete(key);
  }
});

// Binding decisions one at a time: two first reads must not both claim the same unbound tab.
let bindingQueue = Promise.resolve();

function tabFor(key, url) {
  const next = bindingQueue.then(() => resolveTab(key, url));
  bindingQueue = next.catch(() => {});
  return next;
}

async function resolveTab(key, url) {
  const deadline = Date.now() + TAB_LOOKUP_MS;
  for (;;) {
    const bound = tabByKey.get(key);
    if (bound !== undefined) {
      let tab = null;
      try {
        tab = await browser.tabs.get(bound);
      } catch (e) {
        tabByKey.delete(key);
        keyByTab.delete(bound);
      }
      // The bound tab may still be committing a navigation; it never falls back to another tab.
      if (tab && tab.url === url) return tab.id;
      if (tab && Date.now() > deadline) throw new Error('URL_MISMATCH: the page\'s tab shows ' + tab.url);
    } else {
      const candidates = (await browser.tabs.query({}))
          .filter(tab => tab.url === url && !keyByTab.has(tab.id));
      if (candidates.length === 1) {
        tabByKey.set(key, candidates[0].id);
        keyByTab.set(candidates[0].id, key);
        return candidates[0].id;
      }
      if (candidates.length > 1 && Date.now() > deadline) {
        throw new Error('AMBIGUOUS_TAB: ' + candidates.length + ' unbound tabs show ' + url);
      }
      if (candidates.length === 0 && Date.now() > deadline) throw new Error('NO_TAB: no tab shows ' + url);
    }
    await sleep(100);
  }
}

async function runRead(read) {
  let result;
  try {
    const tabId = await tabFor(String(read.tab), read.url);
    // runAt document_start: run at once, also while the page is still loading subresources (as page.evaluate did).
    const values = await browser.tabs.executeScript(tabId, {
      code: '(' + read.script + ')()',
      runAt: 'document_start'
    });
    result = {id: read.id, ok: true, value: values && values.length > 0 ? values[0] : null};
  } catch (e) {
    result = {id: read.id, ok: false, error: String((e && e.message) || e)};
  }
  try {
    await fetch(bridge.base + '/v1/read-result', {
      method: 'POST',
      headers: headers({'Content-Type': 'application/json'}),
      body: JSON.stringify(result)
    });
  } catch (e) {
    // AskAI times the read out; nothing else to do here.
  }
}

// ---------------------------------------------------------------- HUD

function setConnection(next) {
  if (connection !== next) {
    connection = next;
    publish();
  }
}

// One command at a time, in click order: a dragged delay slider sends several SET_DELAY commands, and AskAI applies
// them in arrival order, so the last one sent must also be the last one to arrive.
let commandQueue = Promise.resolve();

function sendCommand(command) {
  if (!bridge || typeof command !== 'string' || command.length === 0 || command.length > 64) return;
  commandQueue = commandQueue.then(() => postCommand(command));
}

async function postCommand(command) {
  try {
    await fetch(bridge.base + '/v1/command', {
      method: 'POST',
      headers: headers({'Content-Type': 'text/plain;charset=UTF-8'}),
      body: command
    });
  } catch (e) {
    // The next poll reports the broken connection; a lost click is shown as such by the panel state.
  }
}

/** Toolbar badge: the wait countdown in red while AskAI waits for the user, a pause mark while paused. */
function updateBadge() {
  let text = '';
  let color = '#2b6cb0';
  let title = 'AskAI Research';
  if (connection !== 'connected') {
    text = connection === 'no-bridge' ? '' : '…';
    title += connection === 'no-bridge' ? ' — not started by AskAI' : ' — connecting';
  } else if (hud) {
    if (!hud.terminal && hud.waiting && hud.countdown >= 0) {
      text = String(hud.countdown);
      color = '#b3261e';
    } else if (!hud.terminal && hud.paused) {
      text = 'II';
    }
    title += ' — ' + hud.phase + (hud.status ? ': ' + hud.status : '');
  }
  browser.browserAction.setBadgeText({text});
  browser.browserAction.setBadgeBackgroundColor({color});
  browser.browserAction.setTitle({title});
}

function snapshot() {
  return {type: 'state', connection, hud};
}

function publish() {
  updateBadge();
  const message = snapshot();
  for (const port of panels) {
    try {
      port.postMessage(message);
    } catch (e) {
      panels.delete(port);
    }
  }
}

browser.runtime.onConnect.addListener(port => {
  if (port.name !== 'askai-panel') return;
  panels.add(port);
  port.onDisconnect.addListener(() => panels.delete(port));
  port.onMessage.addListener(message => {
    if (message && message.type === 'command') sendCommand(message.command);
  });
  port.postMessage(snapshot());
});

(async () => {
  bridge = await loadBridge();
  if (!bridge) {
    setConnection('no-bridge');
    return;
  }
  updateBadge();
  pollLoop();
  readLoop();
})();
