// AskAI Research extension for Chrome/Edge (Manifest V3), service worker. The Chrome counterpart of the Firefox
// add-on's background page, with the same bridge protocol: it long-polls the HUD state from the local bridge
// (127.0.0.1, port and token in bridge.json, written by AskAI at every browser start), posts the user's HUD commands
// back and runs AskAI's read-only page scripts in the tab's isolated content-script world.
//
// Differences to Firefox, all forced by Manifest V3:
// - No code strings: MV3 cannot execute a script given as text. AskAI writes every read script as a file into this
//   extension's own reads/ directory before it queues the read, and the read names that file;
//   the scripting API runs that file in the isolated world and returns its value.
// - A service worker instead of a persistent page: Chrome may stop it when idle. The long polls, an extension API
//   call every 20 s and a 30 s alarm keep it running; if Chrome stops it anyway, the next alarm starts it again and
//   the loops resume (the state poll then simply fetches the newest state).
// - The panel is a side panel (opened with the toolbar button) instead of a sidebar opened at start.
// No content script is registered and no web-accessible resource exists, so a page has nothing of this extension to
// observe.
'use strict';

const PROTOCOL = 1;
const POLL_RETRY_MIN_MS = 500;
const POLL_RETRY_MAX_MS = 5000;
/** How long a read waits for a tab showing its URL (a navigation may just be committing). */
const TAB_LOOKUP_MS = 2000;
/** Only files AskAI wrote into reads/ are ever executed. */
const READ_FILE = /^reads\/[0-9a-f]{32,64}\.js$/;

let bridge = null;          // {base, token} once bridge.json was read
let connection = 'starting'; // starting | connected | retrying | no-bridge
let version = 0;
let hud = null;             // the last HUD state from AskAI
const panels = new Set();

async function loadBridge() {
  try {
    const response = await fetch(chrome.runtime.getURL('bridge.json'), {cache: 'no-store'});
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
    body: JSON.stringify({protocol: PROTOCOL, addonVersion: chrome.runtime.getManifest().version})
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
      await sleep(retry);
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
        runRead(read); // not awaited: reads of different tabs may overlap
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
// the one UNBOUND tab showing the read's URL; every later read of that key goes to exactly that tab. Several unbound
// candidates are refused (AMBIGUOUS_TAB), never guessed, so a read can never return another tab's DOM.
const tabByKey = new Map();   // page key -> tab id
const keyByTab = new Map();   // tab id -> page key
chrome.tabs.onRemoved.addListener(tabId => {
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
        tab = await chrome.tabs.get(bound);
      } catch (e) {
        tabByKey.delete(key);
        keyByTab.delete(bound);
      }
      // The bound tab may still be committing a navigation; it never falls back to another tab.
      if (tab && tab.url === url) return tab.id;
      if (tab && Date.now() > deadline) throw new Error('URL_MISMATCH: the page\'s tab shows ' + tab.url);
    } else {
      const candidates = (await chrome.tabs.query({}))
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
    if (typeof read.file !== 'string' || !READ_FILE.test(read.file)) {
      throw new Error('NO_FILE: the read names no script file of this extension');
    }
    const tabId = await tabFor(String(read.tab), read.url);
    // Top frame only, in the isolated world, at once (also while the page still loads, as page.evaluate did).
    const results = await chrome.scripting.executeScript({
      target: {tabId, frameIds: [0]},
      files: [read.file],
      world: 'ISOLATED',
      injectImmediately: true
    });
    result = {id: read.id, ok: true, value: results && results.length > 0 ? results[0].result : null};
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

// One command at a time, in click order (a dragged delay slider sends several SET_DELAY commands).
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
    // The next poll reports the broken connection.
  }
}

/** The toolbar icon, drawn here because Chrome takes no SVG action icons: the same magnifier as icon.svg. */
function drawIcon(size) {
  const canvas = new OffscreenCanvas(size, size);
  const g = canvas.getContext('2d');
  const s = size / 32;
  g.strokeStyle = '#2b6cb0';
  g.lineCap = 'round';
  g.lineWidth = 4 * s;
  g.beginPath();
  g.arc(13 * s, 13 * s, 9 * s, 0, Math.PI * 2);
  g.stroke();
  g.lineWidth = 5 * s;
  g.beginPath();
  g.moveTo(20 * s, 20 * s);
  g.lineTo(29 * s, 29 * s);
  g.stroke();
  return g.getImageData(0, 0, size, size);
}

function setIcon() {
  try {
    chrome.action.setIcon({imageData: {16: drawIcon(16), 32: drawIcon(32)}});
  } catch (e) {
    // The default puzzle icon stays; the badge still works.
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
  chrome.action.setBadgeText({text});
  chrome.action.setBadgeBackgroundColor({color});
  chrome.action.setTitle({title});
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

chrome.runtime.onConnect.addListener(port => {
  if (port.name !== 'askai-panel') return;
  panels.add(port);
  port.onDisconnect.addListener(() => panels.delete(port));
  port.onMessage.addListener(message => {
    if (message && message.type === 'command') sendCommand(message.command);
  });
  port.postMessage(snapshot());
});

// The toolbar button opens the side panel, which then stays open across navigations.
chrome.sidePanel.setPanelBehavior({openPanelOnActionClick: true}).catch(() => {});

// Keep the service worker alive: every extension API call resets Chrome's idle timer; the alarm restarts a worker
// Chrome stopped anyway (its listener only has to exist: loading this script restarts the loops below).
setInterval(() => chrome.runtime.getPlatformInfo(() => {}), 20000);
chrome.alarms.create('askai-keepalive', {periodInMinutes: 0.5});
chrome.alarms.onAlarm.addListener(() => {});

(async () => {
  setIcon();
  bridge = await loadBridge();
  if (!bridge) {
    setConnection('no-bridge');
    return;
  }
  updateBadge();
  pollLoop();
  readLoop();
})();
