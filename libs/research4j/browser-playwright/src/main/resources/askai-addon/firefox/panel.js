// The HUD view of the AskAI Research add-on, used as toolbar popup and sidebar (Firefox) and as side panel (Chrome). It only renders what the
// background page reports and hands button clicks back to it; it never talks to AskAI or to a page itself.
'use strict';

const $ = id => document.getElementById(id);
// Shared by the Firefox add-on (browser.*) and the Chrome/Edge extension (chrome.*, side panel).
const extension = typeof browser !== 'undefined' ? browser : chrome;
const port = extension.runtime.connect({name: 'askai-panel'});
let paused = false;
let relevant = false;

const command = value => port.postMessage({type: 'command', command: value});

$('pause').addEventListener('click', () => command(paused ? 'RESUME' : 'PAUSE'));
$('skip').addEventListener('click', () => command('SKIP'));
$('next').addEventListener('click', () => command('NEXT'));
$('resolve').addEventListener('click', () => command('NEXT'));
$('star').addEventListener('click', () => command('SET_RELEVANCE:' + (relevant ? 'off' : 'on')));
$('delay').addEventListener('input', () => {
  $('delayval').textContent = $('delay').value;
  command('SET_DELAY:' + $('delay').value);
});

port.onMessage.addListener(message => {
  if (message.type === 'state') render(message.connection, message.hud);
});

function render(connection, hud) {
  const live = connection === 'connected' && hud;
  if (!live) {
    $('phase').textContent = '—';
    $('status').textContent = connection === 'no-bridge'
        ? 'This browser was not started by AskAI.'
        : connection === 'connected' ? 'Connected to AskAI. No research step yet.' : 'Connecting to AskAI…';
    $('host').hidden = true;
    $('wait').hidden = true;
    $('controls').hidden = true;
    $('star').hidden = true;
    return;
  }
  $('phase').textContent = hud.phase || '—';
  $('status').textContent = hud.status || '';
  $('host').textContent = hud.host || '';
  $('host').hidden = hud.terminal || !hud.host;
  // Terminal: the run is over. The browser may stay open, but the HUD offers no controls for a visit that is gone.
  $('controls').hidden = hud.terminal;
  $('star').hidden = hud.terminal;
  // NEXT only skips the inter-page delay, so it exists only while that delay is running.
  $('next').hidden = hud.terminal || hud.phase !== 'DELAY';
  paused = hud.paused;
  $('pause').textContent = paused ? '▶ Resume' : '⏸ Pause';
  relevant = hud.relevant;
  $('star').textContent = relevant ? '★' : '☆';
  // Reflect AskAI's delay, but never while the user drags the slider.
  if (document.activeElement !== $('delay')) {
    $('delay').value = hud.delay;
    $('delayval').textContent = hud.delay;
  }
  const waiting = !hud.terminal && hud.waiting && hud.countdown >= 0;
  $('wait').hidden = !waiting;
  if (waiting) $('count').textContent = hud.countdown;
}
